package com.herostand.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU-side vertex cache for Palladium pack layers.
 *
 * Unlike the failed 0.2.x whole-renderer VBO experiments, this cache never owns OpenGL state,
 * never uploads custom VBOs, and never performs its own translucent sorting. Static Palladium
 * layers are rendered once into primitive CPU arrays; later frames replay those vertices into
 * Minecraft's normal MultiBufferSource so vanilla/Palladium still control RenderTypes, glint,
 * transparency sorting, batching and the final GPU draw.
 */
final class StaticPackVertexCache {
    enum Result {
        HIT,
        BUILT,
        LIVE,
        DEFERRED,
        EMPTY
    }

    private static final long MAX_BYTES = 96L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_BUILDS_PER_TICK = 1;
    private static final long IDLE_TIMEOUT_TICKS = 20L * 120L;
    private static final long SWEEP_INTERVAL_TICKS = 20L * 5L;
    private static final long BLOCKED_RETRY_TICKS = 20L * 30L;

    private final LinkedHashMap<LayerKey, Entry> cache =
            new LinkedHashMap<>(64, 0.75F, true);
    private final LinkedHashMap<LayerKey, Long> blockedUntil =
            new LinkedHashMap<>(64, 0.75F, true);

    // Render thread only: reuse these on every cache hit instead of allocating per layer/frame.
    private final Vector4f positionScratch = new Vector4f();
    private final Vector3f normalScratch = new Vector3f();

    private long cachedBytes;
    private long buildTick = Long.MIN_VALUE;
    private int buildsThisTick;
    private long lastSweepTick = Long.MIN_VALUE;
    private String lastReason = "none";

    Result renderOrBuild(Object layer,
                         ItemStack stack,
                         EquipmentSlot slot,
                         int packedLight,
                         long gameTime,
                         PoseStack outerPose,
                         MultiBufferSource buffers,
                         CaptureRenderer renderer) {
        sweep(gameTime);

        LayerKey key = LayerKey.of(layer, stack, slot, packedLight);
        Entry cached = cache.get(key);
        if (cached != null) {
            cached.lastUsedTick = gameTime;
            cached.layer.replay(
                    outerPose,
                    buffers,
                    positionScratch,
                    normalScratch);
            return Result.HIT;
        }

        Long blocked = blockedUntil.get(key);
        if (blocked != null) {
            if (gameTime < blocked) {
                return Result.LIVE;
            }
            blockedUntil.remove(key);
        }

        if (!consumeBuildBudget(gameTime)) {
            return Result.DEFERRED;
        }

        try {
            CaptureSource firstSource = new CaptureSource();
            renderer.render(firstSource, 0, 0.0F);
            CapturedLayer first = firstSource.freeze();

            if (first.isEmpty()) {
                block(key, gameTime, "empty");
                return Result.EMPTY;
            }

            /*
             * A second capture is safe here because no actual RenderType state is set up and no
             * OpenGL draw occurs: the layer only writes into our recording VertexConsumers.
             * If geometry/texture/render type/color changes with animation time, keep it live.
             */
            CaptureSource secondSource = new CaptureSource();
            renderer.render(secondSource, 7, 0.5F);
            CapturedLayer second = secondSource.freeze();

            if (first.signature != second.signature) {
                block(key, gameTime, "dynamic");
                return Result.LIVE;
            }

            Entry entry = new Entry(first, gameTime);
            cache.put(key, entry);
            cachedBytes += first.bytes;
            trim();

            first.replay(
                    outerPose,
                    buffers,
                    positionScratch,
                    normalScratch);
            lastReason = "none";
            return Result.BUILT;
        } catch (Throwable failure) {
            block(key, gameTime, compactFailure(failure));
            return Result.LIVE;
        }
    }

    void tick(long gameTime) {
        sweep(gameTime);
    }

    void clear() {
        cache.clear();
        blockedUntil.clear();
        cachedBytes = 0L;
        buildTick = Long.MIN_VALUE;
        buildsThisTick = 0;
        lastSweepTick = Long.MIN_VALUE;
        lastReason = "none";
    }

    int size() {
        return cache.size();
    }

    long bytes() {
        return cachedBytes;
    }

    String lastReason() {
        return lastReason;
    }

    private boolean consumeBuildBudget(long gameTime) {
        if (buildTick != gameTime) {
            buildTick = gameTime;
            buildsThisTick = 0;
        }
        if (buildsThisTick >= MAX_BUILDS_PER_TICK) return false;
        buildsThisTick++;
        return true;
    }

    private void block(LayerKey key, long gameTime, String reason) {
        blockedUntil.put(key, gameTime + BLOCKED_RETRY_TICKS);
        lastReason = reason;

        while (blockedUntil.size() > MAX_ENTRIES * 2) {
            Iterator<Map.Entry<LayerKey, Long>> iterator =
                    blockedUntil.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private void trim() {
        Iterator<Map.Entry<LayerKey, Entry>> iterator = cache.entrySet().iterator();
        while ((cache.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES)
                && iterator.hasNext()) {
            Entry eldest = iterator.next().getValue();
            cachedBytes -= eldest.layer.bytes;
            iterator.remove();
        }
    }

    private void sweep(long gameTime) {
        if (lastSweepTick != Long.MIN_VALUE
                && gameTime >= lastSweepTick
                && gameTime - lastSweepTick < SWEEP_INTERVAL_TICKS) {
            return;
        }
        lastSweepTick = gameTime;

        Iterator<Map.Entry<LayerKey, Entry>> cached = cache.entrySet().iterator();
        while (cached.hasNext()) {
            Entry entry = cached.next().getValue();
            long age = gameTime - entry.lastUsedTick;
            if (age < 0L || age >= IDLE_TIMEOUT_TICKS) {
                cachedBytes -= entry.layer.bytes;
                cached.remove();
            }
        }

        Iterator<Map.Entry<LayerKey, Long>> blocked = blockedUntil.entrySet().iterator();
        while (blocked.hasNext()) {
            Map.Entry<LayerKey, Long> next = blocked.next();
            if (gameTime >= next.getValue()) {
                blocked.remove();
            }
        }
    }

    private static String compactFailure(Throwable failure) {
        Throwable root = failure;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 8) {
            root = root.getCause();
        }

        String name = root.getClass().getSimpleName();
        String message = root.getMessage();
        if (message == null || message.isBlank()) return name;

        message = message.replace('\n', ' ').replace('\r', ' ');
        if (message.length() > 28) message = message.substring(0, 28);
        return name + ":" + message;
    }

    @FunctionalInterface
    interface CaptureRenderer {
        void render(MultiBufferSource source,
                    int tickOffset,
                    float partialTick) throws Throwable;
    }

    private static final class Entry {
        final CapturedLayer layer;
        long lastUsedTick;

        Entry(CapturedLayer layer, long lastUsedTick) {
            this.layer = layer;
            this.lastUsedTick = lastUsedTick;
        }
    }

    private static final class LayerKey {
        final Object layer;
        final Item item;
        final int damage;
        final int tagHash;
        final int slot;
        final int packedLight;
        final int hash;

        private LayerKey(Object layer,
                         Item item,
                         int damage,
                         int tagHash,
                         int slot,
                         int packedLight) {
            this.layer = layer;
            this.item = item;
            this.damage = damage;
            this.tagHash = tagHash;
            this.slot = slot;
            this.packedLight = packedLight;

            int h = System.identityHashCode(layer);
            h = 31 * h + System.identityHashCode(item);
            h = 31 * h + damage;
            h = 31 * h + tagHash;
            h = 31 * h + slot;
            h = 31 * h + packedLight;
            this.hash = h;
        }

        static LayerKey of(Object layer,
                           ItemStack stack,
                           EquipmentSlot slot,
                           int packedLight) {
            CompoundTag tag = stack.getTag();
            return new LayerKey(
                    layer,
                    stack.getItem(),
                    stack.getDamageValue(),
                    tag == null ? 0 : tag.hashCode(),
                    slot == null ? -1 : slot.ordinal(),
                    packedLight
            );
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof LayerKey other)) return false;
            return layer == other.layer
                    && item == other.item
                    && damage == other.damage
                    && tagHash == other.tagHash
                    && slot == other.slot
                    && packedLight == other.packedLight;
        }
    }

    private static final class CaptureSource implements MultiBufferSource {
        private final LinkedHashMap<RenderType, VertexBuilder> buckets =
                new LinkedHashMap<>();

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return buckets.computeIfAbsent(renderType, ignored -> new VertexBuilder());
        }

        CapturedLayer freeze() {
            List<VertexBatch> batches = new ArrayList<>(buckets.size());
            long signature = 0xcbf29ce484222325L;
            long bytes = 0L;

            for (Map.Entry<RenderType, VertexBuilder> entry : buckets.entrySet()) {
                VertexBatch batch = entry.getValue().freeze(entry.getKey());
                if (batch.size == 0) continue;

                batches.add(batch);
                signature = mix(signature, entry.getKey().toString().hashCode());
                signature = mix(signature, batch.signature);
                bytes += batch.bytes();
            }

            return new CapturedLayer(List.copyOf(batches), signature, bytes);
        }
    }

    private static final class CapturedLayer {
        final List<VertexBatch> batches;
        final long signature;
        final long bytes;

        CapturedLayer(List<VertexBatch> batches, long signature, long bytes) {
            this.batches = batches;
            this.signature = signature;
            this.bytes = bytes;
        }

        boolean isEmpty() {
            return batches.isEmpty();
        }

        void replay(PoseStack outerPose,
                    MultiBufferSource buffers,
                    Vector4f positionScratch,
                    Vector3f normalScratch) {
            Matrix4f pose = outerPose.last().pose();
            Matrix3f normal = outerPose.last().normal();

            for (VertexBatch batch : batches) {
                batch.replay(
                        pose,
                        normal,
                        positionScratch,
                        normalScratch,
                        buffers.getBuffer(batch.renderType)
                );
            }
        }
    }

    private static final class VertexBatch {
        final RenderType renderType;
        final int size;
        final float[] px;
        final float[] py;
        final float[] pz;
        final float[] u;
        final float[] v;
        final float[] nx;
        final float[] ny;
        final float[] nz;
        final int[] rgba;
        final int[] overlay;
        final int[] light;
        final long signature;

        VertexBatch(RenderType renderType,
                    int size,
                    float[] px,
                    float[] py,
                    float[] pz,
                    float[] u,
                    float[] v,
                    float[] nx,
                    float[] ny,
                    float[] nz,
                    int[] rgba,
                    int[] overlay,
                    int[] light,
                    long signature) {
            this.renderType = renderType;
            this.size = size;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.u = u;
            this.v = v;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.rgba = rgba;
            this.overlay = overlay;
            this.light = light;
            this.signature = signature;
        }

        long bytes() {
            return (long) size * 44L;
        }

        void replay(Matrix4f pose,
                    Matrix3f normalMatrix,
                    Vector4f positionScratch,
                    Vector3f normalScratch,
                    VertexConsumer consumer) {
            for (int i = 0; i < size; i++) {
                positionScratch.set(px[i], py[i], pz[i], 1.0F);
                pose.transform(positionScratch);

                normalScratch.set(nx[i], ny[i], nz[i]);
                normalMatrix.transform(normalScratch);

                int color = rgba[i];
                float red = (color & 0xFF) / 255.0F;
                float green = ((color >>> 8) & 0xFF) / 255.0F;
                float blue = ((color >>> 16) & 0xFF) / 255.0F;
                float alpha = ((color >>> 24) & 0xFF) / 255.0F;

                consumer.vertex(
                        positionScratch.x(),
                        positionScratch.y(),
                        positionScratch.z(),
                        red, green, blue, alpha,
                        u[i], v[i],
                        overlay[i],
                        light[i],
                        normalScratch.x(),
                        normalScratch.y(),
                        normalScratch.z()
                );
            }
        }
    }

    private static final class VertexBuilder implements VertexConsumer {
        private int size;
        private int capacity = 256;

        private float[] px = new float[capacity];
        private float[] py = new float[capacity];
        private float[] pz = new float[capacity];
        private float[] u = new float[capacity];
        private float[] v = new float[capacity];
        private float[] nx = new float[capacity];
        private float[] ny = new float[capacity];
        private float[] nz = new float[capacity];
        private int[] rgba = new int[capacity];
        private int[] overlay = new int[capacity];
        private int[] light = new int[capacity];

        private float cx;
        private float cy;
        private float cz;
        private float cu;
        private float cv;
        private float cnx;
        private float cny;
        private float cnz;
        private int cr = 255;
        private int cg = 255;
        private int cb = 255;
        private int ca = 255;
        private int coverlay;
        private int clight;

        private boolean hasDefaultColor;
        private int defaultR = 255;
        private int defaultG = 255;
        private int defaultB = 255;
        private int defaultA = 255;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            cx = (float) x;
            cy = (float) y;
            cz = (float) z;

            cr = hasDefaultColor ? defaultR : 255;
            cg = hasDefaultColor ? defaultG : 255;
            cb = hasDefaultColor ? defaultB : 255;
            ca = hasDefaultColor ? defaultA : 255;
            cu = 0.0F;
            cv = 0.0F;
            cnx = 0.0F;
            cny = 0.0F;
            cnz = 0.0F;
            coverlay = 0;
            clight = 0;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            cr = red;
            cg = green;
            cb = blue;
            ca = alpha;
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            cu = u;
            cv = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            coverlay = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            clight = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            cnx = x;
            cny = y;
            cnz = z;
            return this;
        }

        @Override
        public void endVertex() {
            ensure(size + 1);

            px[size] = cx;
            py[size] = cy;
            pz[size] = cz;
            u[size] = cu;
            v[size] = cv;
            nx[size] = cnx;
            ny[size] = cny;
            nz[size] = cnz;
            rgba[size] = (cr & 0xFF)
                    | ((cg & 0xFF) << 8)
                    | ((cb & 0xFF) << 16)
                    | ((ca & 0xFF) << 24);
            overlay[size] = coverlay;
            light[size] = clight;
            size++;
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            hasDefaultColor = true;
            defaultR = red;
            defaultG = green;
            defaultB = blue;
            defaultA = alpha;
        }

        @Override
        public void unsetDefaultColor() {
            hasDefaultColor = false;
        }

        VertexBatch freeze(RenderType renderType) {
            float[] outPx = java.util.Arrays.copyOf(px, size);
            float[] outPy = java.util.Arrays.copyOf(py, size);
            float[] outPz = java.util.Arrays.copyOf(pz, size);
            float[] outU = java.util.Arrays.copyOf(u, size);
            float[] outV = java.util.Arrays.copyOf(v, size);
            float[] outNx = java.util.Arrays.copyOf(nx, size);
            float[] outNy = java.util.Arrays.copyOf(ny, size);
            float[] outNz = java.util.Arrays.copyOf(nz, size);
            int[] outRgba = java.util.Arrays.copyOf(rgba, size);
            int[] outOverlay = java.util.Arrays.copyOf(overlay, size);
            int[] outLight = java.util.Arrays.copyOf(light, size);

            long signature = 0xcbf29ce484222325L;
            for (int i = 0; i < size; i++) {
                signature = mix(signature, Float.floatToIntBits(outPx[i]));
                signature = mix(signature, Float.floatToIntBits(outPy[i]));
                signature = mix(signature, Float.floatToIntBits(outPz[i]));
                signature = mix(signature, Float.floatToIntBits(outU[i]));
                signature = mix(signature, Float.floatToIntBits(outV[i]));
                signature = mix(signature, Float.floatToIntBits(outNx[i]));
                signature = mix(signature, Float.floatToIntBits(outNy[i]));
                signature = mix(signature, Float.floatToIntBits(outNz[i]));
                signature = mix(signature, outRgba[i]);
                signature = mix(signature, outOverlay[i]);
                signature = mix(signature, outLight[i]);
            }

            return new VertexBatch(
                    renderType,
                    size,
                    outPx, outPy, outPz,
                    outU, outV,
                    outNx, outNy, outNz,
                    outRgba, outOverlay, outLight,
                    signature
            );
        }

        private void ensure(int required) {
            if (required <= capacity) return;

            int next = capacity;
            while (next < required) next *= 2;
            capacity = next;

            px = java.util.Arrays.copyOf(px, capacity);
            py = java.util.Arrays.copyOf(py, capacity);
            pz = java.util.Arrays.copyOf(pz, capacity);
            u = java.util.Arrays.copyOf(u, capacity);
            v = java.util.Arrays.copyOf(v, capacity);
            nx = java.util.Arrays.copyOf(nx, capacity);
            ny = java.util.Arrays.copyOf(ny, capacity);
            nz = java.util.Arrays.copyOf(nz, capacity);
            rgba = java.util.Arrays.copyOf(rgba, capacity);
            overlay = java.util.Arrays.copyOf(overlay, capacity);
            light = java.util.Arrays.copyOf(light, capacity);
        }
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }
}
