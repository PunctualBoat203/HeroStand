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
import java.util.Objects;

/**
 * GPU-resident cache for the static base armor portion of a HeroStand.
 *
 * Modern GPUs are very good at repeatedly drawing immutable vertex/index buffers. The expensive
 * part of HeroStand's current mixed-suit path is rebuilding the same armor model vertices through
 * Java/Palladium every frame. This cache builds those base-armor vertices occasionally, uploads
 * them as STATIC VertexBuffers, and reuses them until the equipment/light variant expires.
 *
 * Pack layers are intentionally NOT baked here yet. They can contain animated/custom effects and
 * continue through Palladium's normal live path for visual correctness.
 */
final class StaticArmorVboCache implements AutoCloseable {
    private static final long BASE_LIFETIME_TICKS = 40L;
    private static final long LIFETIME_SPREAD_TICKS = 20L;

    private final int maxEntries;
    private final int maxBuildsPerTick;

    private final LinkedHashMap<SuitKey, Entry> entries =
            new LinkedHashMap<>(32, 0.75F, true);

    private long buildTick = Long.MIN_VALUE;
    private int buildsThisTick;

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
        SuitKey key = SuitKey.from(renderContext, packedLight);

        Entry cached = entries.get(key);
        if (cached != null && gameTime < cached.expiresAt) {
            cached.draw(worldPose);
            return true;
        }

        if (cached != null) {
            entries.remove(key);
            cached.close();
        }

        if (!consumeBuildBudget(gameTime)) {
            return false;
        }

        Entry built = build(renderer, palladium, renderContext, packedLight, partialTick, gameTime, key);
        if (built == null) {
            return false;
        }

        entries.put(key, built);
        evictIfNeeded();
        built.draw(worldPose);
        return true;
    }

    void clear() {
        for (Entry entry : entries.values()) {
            entry.close();
        }
        entries.clear();
        buildTick = Long.MIN_VALUE;
        buildsThisTick = 0;
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
                        long gameTime,
                        SuitKey key) {
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

            long expiresAt = gameTime + lifetimeFor(key.hashCode());
            return new Entry(parts, expiresAt);
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

    private void evictIfNeeded() {
        while (entries.size() > maxEntries) {
            Iterator<Map.Entry<SuitKey, Entry>> iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) return;

            Map.Entry<SuitKey, Entry> eldest = iterator.next();
            iterator.remove();
            eldest.getValue().close();
        }
    }

    private static long lifetimeFor(int hash) {
        return BASE_LIFETIME_TICKS
                + (Integer.toUnsignedLong(hash) % LIFETIME_SPREAD_TICKS);
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
        private final long expiresAt;

        Entry(List<MeshPart> parts, long expiresAt) {
            this.parts = List.copyOf(parts);
            this.expiresAt = expiresAt;
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

    private record SuitKey(
            SlotKey head,
            SlotKey chest,
            SlotKey legs,
            SlotKey feet,
            int packedLight
    ) {
        static SuitKey from(ArmorStand entity, int packedLight) {
            return new SuitKey(
                    SlotKey.from(entity.getItemBySlot(EquipmentSlot.HEAD)),
                    SlotKey.from(entity.getItemBySlot(EquipmentSlot.CHEST)),
                    SlotKey.from(entity.getItemBySlot(EquipmentSlot.LEGS)),
                    SlotKey.from(entity.getItemBySlot(EquipmentSlot.FEET)),
                    packedLight
            );
        }
    }

    private record SlotKey(Item item, int damage, CompoundTag tag) {
        static SlotKey from(ItemStack stack) {
            if (stack.isEmpty()) return null;

            CompoundTag tag = stack.getTag();
            return new SlotKey(
                    stack.getItem(),
                    stack.getDamageValue(),
                    tag == null ? null : tag.copy()
            );
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof SlotKey other)) return false;
            return item == other.item
                    && damage == other.damage
                    && Objects.equals(tag, other.tag);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(item);
            result = 31 * result + damage;
            result = 31 * result + Objects.hashCode(tag);
            return result;
        }
    }
}
