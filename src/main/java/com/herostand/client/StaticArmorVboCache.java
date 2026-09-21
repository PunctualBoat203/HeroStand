package com.herostand.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
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
 * GPU-resident cache for the static base-armor portion of a HeroStand.
 *
 * 0.1.16 lifecycle policy:
 * - active entries do NOT expire just because a timer elapsed;
 * - exact equipment/NBT/light matches reuse the same GPU buffers;
 * - unused entries are swept after an idle timeout;
 * - each suit identity may keep only a small number of lighting variants;
 * - a global access-ordered LRU cap is the hard VRAM ceiling;
 * - every eviction explicitly closes the underlying OpenGL VertexBuffers.
 */
final class StaticArmorVboCache implements AutoCloseable {
    private static final long IDLE_TIMEOUT_TICKS = 20L * 45L; // 45 seconds unused
    private static final long SWEEP_INTERVAL_TICKS = 20L * 5L; // sweep every 5 seconds
    private static final int MAX_LIGHT_VARIANTS_PER_SUIT = 4;

    private final int maxEntries;
    private final int maxBuildsPerTick;

    /**
     * accessOrder=true means the first entry is always the least recently touched entry.
     */
    private final LinkedHashMap<CacheKey, Entry> entries =
            new LinkedHashMap<>(32, 0.75F, true);

    private long buildTick = Long.MIN_VALUE;
    private int buildsThisTick;
    private long lastSweepTick = Long.MIN_VALUE;

    StaticArmorVboCache(int maxEntries, int maxBuildsPerTick) {
        this.maxEntries = Math.max(16, maxEntries);
        this.maxBuildsPerTick = Math.max(1, maxBuildsPerTick);
    }

    boolean renderOrBuild(HeroStandRenderer renderer,
                          PalladiumRenderBridge palladium,
                          ArmorStand renderContext,
                          PoseStack worldPose,
                          int packedLight,
                          float partialTick) {
        long gameTime = renderContext.level().getGameTime();
        sweepIdle(gameTime);

        SuitIdentity suit = SuitIdentity.from(renderContext);
        CacheKey key = new CacheKey(suit, packedLight);

        Entry cached = entries.get(key);
        if (cached != null) {
            cached.touch(gameTime);
            cached.draw(worldPose);
            return true;
        }

        if (!consumeBuildBudget(gameTime)) {
            return false;
        }

        Entry built = build(
                renderer,
                palladium,
                renderContext,
                packedLight,
                partialTick,
                gameTime
        );
        if (built == null) {
            return false;
        }

        // Do not let one suit accumulate dozens of nearly-identical lighting meshes.
        trimLightVariantsBeforeInsert(suit);

        entries.put(key, built);
        evictGlobalIfNeeded();
        built.draw(worldPose);
        return true;
    }

    /**
     * Called from the client tick as well as renderOrBuild so meshes age out even when the player
     * walks far enough away that no HeroStand is currently being rendered.
     */
    void tick(long gameTime) {
        sweepIdle(gameTime);
    }

    int size() {
        return entries.size();
    }

    void clear() {
        for (Entry entry : entries.values()) {
            entry.close();
        }
        entries.clear();
        buildTick = Long.MIN_VALUE;
        buildsThisTick = 0;
        lastSweepTick = Long.MIN_VALUE;
    }

    @Override
    public void close() {
        clear();
    }

    private Entry build(HeroStandRenderer renderer,
                        PalladiumRenderBridge palladium,
                        ArmorStand renderContext,
                        int packedLight,
                        float partialTick,
                        long gameTime) {
        CaptureSource capture = new CaptureSource();
        PoseStack localPose = new PoseStack();
        HeroStandRenderer.applyManualPalladiumTransform(localPose);

        try {
            boolean directArmor = palladium.renderArmorDirect(
                    renderContext,
                    renderer.parentModelForCache(),
                    renderer.innerArmorModelForCache(),
                    renderer.outerArmorModelForCache(),
                    localPose,
                    capture,
                    packedLight,
                    partialTick
            );

            if (!directArmor) {
                capture.discard();
                return null;
            }

            List<MeshPart> parts = capture.upload();
            if (parts.isEmpty()) {
                return null;
            }

            return new Entry(parts, gameTime);
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

        if (buildsThisTick >= maxBuildsPerTick) {
            return false;
        }

        buildsThisTick++;
        return true;
    }

    private void sweepIdle(long gameTime) {
        if (lastSweepTick != Long.MIN_VALUE
                && gameTime >= lastSweepTick
                && gameTime - lastSweepTick < SWEEP_INTERVAL_TICKS) {
            return;
        }

        lastSweepTick = gameTime;

        Iterator<Map.Entry<CacheKey, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheKey, Entry> next = iterator.next();
            Entry entry = next.getValue();

            // Also handles a world-time rewind defensively by clearing entries whose timestamps
            // are now in the future.
            long age = gameTime - entry.lastUsedTick;
            if (age < 0L || age >= IDLE_TIMEOUT_TICKS) {
                iterator.remove();
                entry.close();
            }
        }
    }

    private void trimLightVariantsBeforeInsert(SuitIdentity suit) {
        int variants = 0;
        for (CacheKey key : entries.keySet()) {
            if (key.suit.equals(suit)) {
                variants++;
            }
        }

        while (variants >= MAX_LIGHT_VARIANTS_PER_SUIT) {
            Iterator<Map.Entry<CacheKey, Entry>> iterator = entries.entrySet().iterator();
            boolean removed = false;

            while (iterator.hasNext()) {
                Map.Entry<CacheKey, Entry> next = iterator.next();
                if (!next.getKey().suit.equals(suit)) continue;

                iterator.remove();
                next.getValue().close();
                variants--;
                removed = true;
                break;
            }

            if (!removed) break;
        }
    }

    private void evictGlobalIfNeeded() {
        while (entries.size() > maxEntries) {
            Iterator<Map.Entry<CacheKey, Entry>> iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) return;

            Map.Entry<CacheKey, Entry> eldest = iterator.next();
            iterator.remove();
            eldest.getValue().close();
        }
    }

    private static final class CaptureSource implements MultiBufferSource {
        private final LinkedHashMap<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        private boolean finished;

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            if (finished) {
                throw new IllegalStateException("HeroStand static mesh capture is already finished");
            }

            return builders.computeIfAbsent(renderType, type -> {
                BufferBuilder builder = new BufferBuilder(Math.max(256, type.bufferSize()));
                builder.begin(type.mode(), type.format());
                return builder;
            });
        }

        List<MeshPart> upload() {
            finished = true;
            List<MeshPart> result = new ArrayList<>(builders.size());

            try {
                for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                    BufferBuilder.RenderedBuffer rendered = entry.getValue().endOrDiscardIfEmpty();
                    if (rendered == null) continue;

                    VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    try {
                        vertexBuffer.bind();
                        vertexBuffer.upload(rendered);
                        VertexBuffer.unbind();
                        result.add(new MeshPart(entry.getKey(), vertexBuffer));
                    } catch (Throwable uploadFailure) {
                        VertexBuffer.unbind();
                        vertexBuffer.close();
                        throw uploadFailure;
                    }
                }
            } catch (Throwable failure) {
                for (MeshPart part : result) {
                    part.close();
                }
                throw failure;
            } finally {
                builders.clear();
            }

            return result;
        }

        void discard() {
            if (finished) return;
            finished = true;

            for (BufferBuilder builder : builders.values()) {
                try {
                    BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
                    if (rendered != null) rendered.release();
                } catch (Throwable ignored) {
                }
            }
            builders.clear();
        }
    }

    private static final class Entry implements AutoCloseable {
        private final List<MeshPart> parts;
        private long lastUsedTick;

        Entry(List<MeshPart> parts, long lastUsedTick) {
            this.parts = List.copyOf(parts);
            this.lastUsedTick = lastUsedTick;
        }

        void touch(long gameTime) {
            this.lastUsedTick = gameTime;
        }

        void draw(PoseStack worldPose) {
            Matrix4f modelView =
                    new Matrix4f(RenderSystem.getModelViewMatrix()).mul(worldPose.last().pose());

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
        private final VertexBuffer vertexBuffer;

        MeshPart(RenderType renderType, VertexBuffer vertexBuffer) {
            this.renderType = renderType;
            this.vertexBuffer = vertexBuffer;
        }

        void draw(Matrix4f modelView) {
            renderType.setupRenderState();
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) {
                    throw new IllegalStateException("RenderType did not provide a shader");
                }

                vertexBuffer.bind();
                vertexBuffer.drawWithShader(
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
            vertexBuffer.close();
        }
    }

    private record CacheKey(SuitIdentity suit, int packedLight) {}

    /**
     * Compact immutable identity. NBT is represented by its content hash so generating the lookup
     * key does not copy four CompoundTags every frame. Four independent 64-bit slot fingerprints
     * make accidental collisions vanishingly unlikely for a render cache.
     */
    private record SuitIdentity(long head, long chest, long legs, long feet) {
        static SuitIdentity from(ArmorStand entity) {
            return new SuitIdentity(
                    fingerprint(entity.getItemBySlot(EquipmentSlot.HEAD)),
                    fingerprint(entity.getItemBySlot(EquipmentSlot.CHEST)),
                    fingerprint(entity.getItemBySlot(EquipmentSlot.LEGS)),
                    fingerprint(entity.getItemBySlot(EquipmentSlot.FEET))
            );
        }

        private static long fingerprint(ItemStack stack) {
            if (stack.isEmpty()) return 0L;

            Item item = stack.getItem();
            long value = Integer.toUnsignedLong(System.identityHashCode(item));
            value = mix(value ^ Integer.toUnsignedLong(stack.getDamageValue()) * 0x9E3779B9L);

            if (stack.hasTag()) {
                value = mix(value ^ Integer.toUnsignedLong(stack.getTag().hashCode()) * 0xC2B2AE35L);
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
