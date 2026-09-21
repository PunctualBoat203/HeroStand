package com.herostand.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete static-suit snapshot cache.
 *
 * Unlike the 0.1.x VBO experiment, this captures the complete native Palladium SuitStand visual
 * (base armor + known-static pack layers) in one pass. The snapshot is then reused for every stand
 * wearing the same suit under the same packed-light value.
 *
 * True translucent/dynamic visuals are never frozen: if capture sees a RenderType that still needs
 * translucent sorting after conservative alpha classification, the snapshot is rejected and that
 * suit remains on Palladium's live renderer.
 */
final class StaticSuitSnapshotCache implements AutoCloseable {
    private static final int MAX_ENTRIES = 192;
    private static final int MAX_LIGHT_VARIANTS_PER_SUIT = 4;
    private static final int MAX_BUILDS_PER_TICK = 2;
    private static final long IDLE_TIMEOUT_TICKS = 20L * 120L;
    private static final long SWEEP_INTERVAL_TICKS = 20L * 5L;
    private static final long RETRY_UNCACHEABLE_TICKS = 20L * 30L;

    private final LinkedHashMap<CacheKey, Snapshot> snapshots =
            new LinkedHashMap<>(64, 0.75F, true);
    private final LinkedHashMap<SuitIdentity, Long> uncacheableUntil =
            new LinkedHashMap<>(64, 0.75F, true);

    private final SnapshotRenderTypeResolver renderTypeResolver =
            new SnapshotRenderTypeResolver();

    private long buildTick = Long.MIN_VALUE;
    private int buildsThisTick;
    private long lastSweepTick = Long.MIN_VALUE;

    /**
     * Fast path used every frame. It performs only the compact suit/light key lookup and never
     * touches Palladium reflection/model inspection.
     */
    boolean renderCached(ArmorStand suitStand,
                         int packedLight,
                         long gameTime,
                         PoseStack worldPose) {
        sweep(gameTime);

        CacheKey key =
                new CacheKey(SuitIdentity.from(suitStand), packedLight);
        Snapshot cached = snapshots.get(key);
        if (cached == null) return false;

        cached.lastUsedTick = gameTime;
        cached.draw(worldPose);
        return true;
    }

    /**
     * Called only after the bridge has decided an uncached suit is snapshot-safe.
     *
     * @return true when a cached/new snapshot was drawn; false means caller should live-render.
     */
    boolean renderOrBuild(ArmorStand suitStand,
                          int packedLight,
                          long gameTime,
                          PoseStack worldPose,
                          SnapshotRenderer renderer) {
        sweep(gameTime);

        SuitIdentity suit = SuitIdentity.from(suitStand);
        CacheKey key = new CacheKey(suit, packedLight);

        Snapshot cached = snapshots.get(key);
        if (cached != null) {
            cached.lastUsedTick = gameTime;
            cached.draw(worldPose);
            return true;
        }

        Long blockedUntil = uncacheableUntil.get(suit);
        if (blockedUntil != null) {
            if (gameTime < blockedUntil) return false;
            uncacheableUntil.remove(suit);
        }

        if (!consumeBuildBudget(gameTime)) {
            return false;
        }

        Snapshot built = build(renderer, gameTime);
        if (built == null) {
            rememberUncacheable(suit, gameTime);
            return false;
        }

        trimLightVariantsBeforeInsert(suit);
        snapshots.put(key, built);
        evictGlobalIfNeeded();

        built.draw(worldPose);
        return true;
    }

    void tick(long gameTime) {
        sweep(gameTime);
    }

    void clear() {
        for (Snapshot snapshot : snapshots.values()) {
            snapshot.close();
        }
        snapshots.clear();
        uncacheableUntil.clear();
        renderTypeResolver.clear();

        buildTick = Long.MIN_VALUE;
        buildsThisTick = 0;
        lastSweepTick = Long.MIN_VALUE;
    }

    int size() {
        return snapshots.size();
    }

    @Override
    public void close() {
        clear();
    }

    private Snapshot build(SnapshotRenderer renderer, long gameTime) {
        CaptureSource capture = new CaptureSource(renderTypeResolver);
        PoseStack localPose = new PoseStack();

        try {
            renderer.render(localPose, capture);

            if (!capture.snapshotSafe()) {
                capture.discard();
                return null;
            }

            List<MeshPart> parts = capture.upload();
            if (parts.isEmpty()) {
                return null;
            }

            return new Snapshot(parts, gameTime);
        } catch (Throwable failure) {
            capture.discard();
            return null;
        }
    }

    private boolean consumeBuildBudget(long gameTime) {
        if (buildTick != gameTime) {
            buildTick = gameTime;
            buildsThisTick = 0;
        }

        if (buildsThisTick >= MAX_BUILDS_PER_TICK) {
            return false;
        }

        buildsThisTick++;
        return true;
    }

    private void rememberUncacheable(SuitIdentity suit, long gameTime) {
        uncacheableUntil.put(suit, gameTime + RETRY_UNCACHEABLE_TICKS);

        while (uncacheableUntil.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<SuitIdentity, Long>> iterator =
                    uncacheableUntil.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
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

        Iterator<Map.Entry<CacheKey, Snapshot>> iterator =
                snapshots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheKey, Snapshot> next = iterator.next();
            Snapshot snapshot = next.getValue();
            long age = gameTime - snapshot.lastUsedTick;

            if (age < 0L || age >= IDLE_TIMEOUT_TICKS) {
                iterator.remove();
                snapshot.close();
            }
        }

        Iterator<Map.Entry<SuitIdentity, Long>> blocked =
                uncacheableUntil.entrySet().iterator();
        while (blocked.hasNext()) {
            Map.Entry<SuitIdentity, Long> next = blocked.next();
            if (gameTime >= next.getValue()) {
                blocked.remove();
            }
        }
    }

    private void trimLightVariantsBeforeInsert(SuitIdentity suit) {
        int count = 0;
        for (CacheKey key : snapshots.keySet()) {
            if (key.suit.equals(suit)) count++;
        }

        while (count >= MAX_LIGHT_VARIANTS_PER_SUIT) {
            Iterator<Map.Entry<CacheKey, Snapshot>> iterator =
                    snapshots.entrySet().iterator();
            boolean removed = false;

            while (iterator.hasNext()) {
                Map.Entry<CacheKey, Snapshot> next = iterator.next();
                if (!next.getKey().suit.equals(suit)) continue;

                iterator.remove();
                next.getValue().close();
                count--;
                removed = true;
                break;
            }

            if (!removed) break;
        }
    }

    private void evictGlobalIfNeeded() {
        while (snapshots.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<CacheKey, Snapshot>> iterator =
                    snapshots.entrySet().iterator();
            if (!iterator.hasNext()) return;

            Map.Entry<CacheKey, Snapshot> eldest = iterator.next();
            iterator.remove();
            eldest.getValue().close();
        }
    }

    @FunctionalInterface
    interface SnapshotRenderer {
        void render(PoseStack localPose, MultiBufferSource captureSource);
    }

    private static final class CaptureSource implements MultiBufferSource {
        private final LinkedHashMap<RenderType, Bucket> buckets =
                new LinkedHashMap<>();
        private final SnapshotRenderTypeResolver resolver;

        private boolean finished;
        private boolean safe = true;

        CaptureSource(SnapshotRenderTypeResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            if (finished) {
                throw new IllegalStateException(
                        "HeroStand snapshot capture already finished");
            }

            Bucket bucket = buckets.get(renderType);
            if (bucket == null) {
                BufferBuilder builder =
                        new BufferBuilder(Math.max(256, renderType.bufferSize()));
                builder.begin(renderType.mode(), renderType.format());

                bucket = new Bucket(renderType, builder);
                buckets.put(renderType, bucket);
            }

            return bucket.consumer;
        }

        boolean snapshotSafe() {
            if (!safe) return false;

            for (Bucket bucket : buckets.values()) {
                RenderType drawType =
                        resolver.resolve(bucket.originalType, bucket.partialVertexAlpha);

                /*
                 * Camera-relative translucent sorting is not stable once geometry is cached in a
                 * reusable local-space VBO. If it still needs sorting after alpha classification,
                 * keep this suit on Palladium's live renderer.
                 */
                if (drawType.sortOnUpload()) {
                    safe = false;
                    return false;
                }

                bucket.drawType = drawType;
            }

            return true;
        }

        List<MeshPart> upload() {
            finished = true;
            List<MeshPart> result = new ArrayList<>(buckets.size());

            try {
                for (Bucket bucket : buckets.values()) {
                    BufferBuilder.RenderedBuffer rendered =
                            bucket.builder.endOrDiscardIfEmpty();
                    if (rendered == null) continue;

                    VertexBuffer vbo =
                            new VertexBuffer(VertexBuffer.Usage.STATIC);

                    try {
                        vbo.bind();
                        vbo.upload(rendered);
                        VertexBuffer.unbind();

                        result.add(new MeshPart(bucket.drawType, vbo));
                    } catch (Throwable failure) {
                        VertexBuffer.unbind();
                        vbo.close();
                        throw failure;
                    }
                }
            } catch (Throwable failure) {
                for (MeshPart part : result) {
                    part.close();
                }
                throw failure;
            } finally {
                buckets.clear();
            }

            return result;
        }

        void discard() {
            if (finished) return;
            finished = true;

            for (Bucket bucket : buckets.values()) {
                if (!bucket.builder.building()) continue;

                try {
                    BufferBuilder.RenderedBuffer rendered =
                            bucket.builder.endOrDiscardIfEmpty();
                    if (rendered != null) rendered.release();
                } catch (Throwable ignored) {
                }
            }

            buckets.clear();
        }

        private static final class Bucket {
            final RenderType originalType;
            final BufferBuilder builder;
            final VertexConsumer consumer;

            RenderType drawType;
            boolean partialVertexAlpha;

            Bucket(RenderType originalType, BufferBuilder builder) {
                this.originalType = originalType;
                this.drawType = originalType;
                this.builder = builder;
                this.consumer = new AlphaTrackingConsumer(builder, this);
            }

            void observeAlpha(int alpha) {
                if (alpha > 0 && alpha < 255) {
                    partialVertexAlpha = true;
                }
            }
        }

        private static final class AlphaTrackingConsumer
                implements VertexConsumer {
            private final VertexConsumer delegate;
            private final Bucket bucket;

            AlphaTrackingConsumer(VertexConsumer delegate, Bucket bucket) {
                this.delegate = delegate;
                this.bucket = bucket;
            }

            @Override
            public VertexConsumer vertex(double x, double y, double z) {
                delegate.vertex(x, y, z);
                return this;
            }

            @Override
            public VertexConsumer color(
                    int red, int green, int blue, int alpha) {
                bucket.observeAlpha(alpha);
                delegate.color(red, green, blue, alpha);
                return this;
            }

            @Override
            public VertexConsumer uv(float u, float v) {
                delegate.uv(u, v);
                return this;
            }

            @Override
            public VertexConsumer overlayCoords(int u, int v) {
                delegate.overlayCoords(u, v);
                return this;
            }

            @Override
            public VertexConsumer uv2(int u, int v) {
                delegate.uv2(u, v);
                return this;
            }

            @Override
            public VertexConsumer normal(float x, float y, float z) {
                delegate.normal(x, y, z);
                return this;
            }

            @Override
            public void endVertex() {
                delegate.endVertex();
            }

            @Override
            public void defaultColor(
                    int red, int green, int blue, int alpha) {
                bucket.observeAlpha(alpha);
                delegate.defaultColor(red, green, blue, alpha);
            }

            @Override
            public void unsetDefaultColor() {
                delegate.unsetDefaultColor();
            }
        }
    }

    private static final class Snapshot implements AutoCloseable {
        private final List<MeshPart> parts;
        private long lastUsedTick;

        Snapshot(List<MeshPart> parts, long gameTime) {
            this.parts = List.copyOf(parts);
            this.lastUsedTick = gameTime;
        }

        void draw(PoseStack worldPose) {
            Matrix4f modelView =
                    new Matrix4f(RenderSystem.getModelViewMatrix())
                            .mul(worldPose.last().pose());

            for (MeshPart part : parts) {
                part.draw(modelView);
            }
        }

        @Override
        public void close() {
            for (MeshPart part : parts) {
                part.close();
            }
        }
    }

    private static final class MeshPart implements AutoCloseable {
        private final RenderType renderType;
        private final VertexBuffer vbo;

        MeshPart(RenderType renderType, VertexBuffer vbo) {
            this.renderType = renderType;
            this.vbo = vbo;
        }

        void draw(Matrix4f modelView) {
            renderType.setupRenderState();
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) {
                    throw new IllegalStateException(
                            "RenderType did not provide a shader");
                }

                vbo.bind();
                vbo.drawWithShader(
                        modelView,
                        RenderSystem.getProjectionMatrix(),
                        shader
                );
            } finally {
                VertexBuffer.unbind();
                renderType.clearRenderState();
            }
        }

        @Override
        public void close() {
            vbo.close();
        }
    }

    private record CacheKey(SuitIdentity suit, int packedLight) {}

    private record SuitIdentity(
            long head,
            long chest,
            long legs,
            long feet
    ) {
        static SuitIdentity from(ArmorStand stand) {
            return new SuitIdentity(
                    fingerprint(stand.getItemBySlot(EquipmentSlot.HEAD)),
                    fingerprint(stand.getItemBySlot(EquipmentSlot.CHEST)),
                    fingerprint(stand.getItemBySlot(EquipmentSlot.LEGS)),
                    fingerprint(stand.getItemBySlot(EquipmentSlot.FEET))
            );
        }

        private static long fingerprint(ItemStack stack) {
            if (stack.isEmpty()) return 0L;

            Item item = stack.getItem();
            long value =
                    Integer.toUnsignedLong(System.identityHashCode(item));

            value = mix(value
                    ^ Integer.toUnsignedLong(stack.getDamageValue())
                    * 0x9E3779B9L);

            CompoundTag tag = stack.getTag();
            if (tag != null) {
                value = mix(value
                        ^ Integer.toUnsignedLong(tag.hashCode())
                        * 0xC2B2AE35L);
            }

            return value;
        }

        private static long mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return value;
        }
    }
}
