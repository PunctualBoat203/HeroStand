package com.herostand.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HeroStand-specific immediate-mode batcher.
 *
 * Minecraft's normal BufferSource owns one fallback BufferBuilder. When block/entity rendering
 * alternates between many RenderTypes (especially many Palladium suit textures), that fallback
 * buffer is ended and drawn repeatedly. A showroom with many suits therefore turns into lots of
 * small draw calls and state changes.
 *
 * This class keeps one builder per consolidatable RenderType for the HeroStand block-entity pass
 * and flushes each type once at AFTER_BLOCK_ENTITIES. It is intentionally small and conservative:
 * non-consolidatable types and unusual wrapper buffer sources go straight to Minecraft's original
 * source for correctness.
 */
final class HeroStandBatchBufferSource implements MultiBufferSource {
    private static final int MAX_BATCHED_RENDER_TYPES = 256;

    private final LinkedHashMap<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
    private final LinkedHashMap<RenderType, Boolean> active = new LinkedHashMap<>();

    private MultiBufferSource delegate;

    MultiBufferSource wrap(MultiBufferSource source) {
        // Destruction overlays and other wrappers can depend on intercepting getBuffer(). Do not
        // bypass those. The normal level renderer passes a BufferSource for ordinary block entities.
        if (!(source instanceof MultiBufferSource.BufferSource)) {
            return source;
        }

        this.delegate = source;
        return this;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        MultiBufferSource fallback = this.delegate;
        if (fallback == null) {
            throw new IllegalStateException("HeroStand batch source used outside a render pass");
        }

        if (!renderType.canConsolidateConsecutiveGeometry()) {
            return fallback.getBuffer(renderType);
        }

        BufferBuilder builder = builders.get(renderType);
        if (builder == null) {
            if (builders.size() >= MAX_BATCHED_RENDER_TYPES) {
                return fallback.getBuffer(renderType);
            }

            builder = new BufferBuilder(Math.max(256, renderType.bufferSize()));
            builders.put(renderType, builder);
        }

        if (!builder.building()) {
            builder.begin(renderType.mode(), renderType.format());
            active.put(renderType, Boolean.TRUE);
        }

        return builder;
    }

    void flush() {
        if (active.isEmpty()) {
            delegate = null;
            return;
        }

        /*
         * Preserve first-seen RenderType order. Every type is submitted only once here. RenderType
         * itself still performs its normal quad sorting when required, so translucent types keep
         * Minecraft's upload-time sorting behavior.
         */
        for (RenderType renderType : active.keySet()) {
            BufferBuilder builder = builders.get(renderType);
            if (builder != null && builder.building()) {
                renderType.end(builder, RenderSystem.getVertexSorting());
            }
        }

        active.clear();
        delegate = null;
    }

    void discard() {
        for (Map.Entry<RenderType, Boolean> entry : active.entrySet()) {
            BufferBuilder builder = builders.get(entry.getKey());
            if (builder == null || !builder.building()) continue;

            try {
                BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
                if (rendered != null) rendered.release();
            } catch (Throwable ignored) {
            }
        }

        active.clear();
        delegate = null;
    }

    int retainedRenderTypes() {
        return builders.size();
    }
}
