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
 * Besides batching by RenderType, this class can safely demote plain entity_translucent geometry
 * to entity_cutout_no_cull when BOTH the texture alpha and submitted vertex alpha are binary.
 * That removes blending and translucent quad sorting for ordinary "solid" Palladium pack layers
 * without breaking layers that actually fade.
 */
final class HeroStandBatchBufferSource implements MultiBufferSource {
    private static final int MAX_BATCHED_RENDER_TYPES = 256;

    private final LinkedHashMap<RenderType, BatchState> states = new LinkedHashMap<>();
    private final LinkedHashMap<RenderType, BatchState> active = new LinkedHashMap<>();
    private final AlphaAwareRenderTypeOptimizer alphaOptimizer =
            new AlphaAwareRenderTypeOptimizer();

    private MultiBufferSource delegate;

    MultiBufferSource wrap(MultiBufferSource source) {
        /*
         * Destruction overlays and other wrappers can depend on intercepting getBuffer(). The
         * normal LevelRenderer path passes an exact BufferSource. If another optimization mod has
         * replaced it with a subclass/wrapper, leave batching to that mod instead of double-buffering.
         */
        if (source == null || source.getClass() != MultiBufferSource.BufferSource.class) {
            return source;
        }

        this.delegate = source;
        return this;
    }

    @Override
    public VertexConsumer getBuffer(RenderType requestedType) {
        MultiBufferSource fallback = this.delegate;
        if (fallback == null) {
            throw new IllegalStateException("HeroStand batch source used outside a render pass");
        }

        // Base Palladium armor is known to submit vertex alpha 1.0, so this replacement is safe
        // immediately when the texture itself contains only binary alpha.
        RenderType renderType = alphaOptimizer.optimize(requestedType);

        if (!renderType.canConsolidateConsecutiveGeometry()) {
            return fallback.getBuffer(renderType);
        }

        BatchState state = states.get(renderType);
        if (state == null) {
            if (states.size() >= MAX_BATCHED_RENDER_TYPES) {
                return fallback.getBuffer(renderType);
            }

            BufferBuilder builder =
                    new BufferBuilder(Math.max(256, renderType.bufferSize()));
            RenderType deferredCutout =
                    alphaOptimizer.deferredBinaryCutoutCandidate(renderType);
            state = new BatchState(renderType, deferredCutout, builder);
            states.put(renderType, state);
        }

        if (!state.builder.building()) {
            state.builder.begin(renderType.mode(), renderType.format());
            state.beginFrame();
            active.put(renderType, state);
        }

        return state.consumer;
    }

    void flush() {
        if (active.isEmpty()) {
            delegate = null;
            return;
        }

        /*
         * Preserve first-seen RenderType order. When a texture is binary-alpha and every submitted
         * vertex also used alpha 0/255, use the cutout candidate at flush time. This specifically
         * avoids entity_translucent's alpha blending and quad-sorting allocations for truly solid
         * Palladium pack layers.
         */
        for (BatchState state : active.values()) {
            if (!state.builder.building()) continue;

            RenderType flushType =
                    state.canUseDeferredCutout()
                            ? state.deferredCutout
                            : state.originalType;

            flushType.end(state.builder, RenderSystem.getVertexSorting());
        }

        active.clear();
        delegate = null;
    }

    void clearOptimizationCaches() {
        alphaOptimizer.clear();
    }

    void discard() {
        for (BatchState state : active.values()) {
            if (!state.builder.building()) continue;

            try {
                BufferBuilder.RenderedBuffer rendered =
                        state.builder.endOrDiscardIfEmpty();
                if (rendered != null) rendered.release();
            } catch (Throwable ignored) {
            }
        }

        active.clear();
        delegate = null;
    }

    int retainedRenderTypes() {
        return states.size();
    }

    private static final class BatchState {
        final RenderType originalType;
        final RenderType deferredCutout;
        final BufferBuilder builder;
        final VertexConsumer consumer;

        boolean partialVertexAlpha;

        BatchState(RenderType originalType,
                   RenderType deferredCutout,
                   BufferBuilder builder) {
            this.originalType = originalType;
            this.deferredCutout = deferredCutout;
            this.builder = builder;
            this.consumer = deferredCutout == null
                    ? builder
                    : new AlphaTrackingConsumer(builder, this);
        }

        void beginFrame() {
            partialVertexAlpha = false;
        }

        boolean canUseDeferredCutout() {
            return deferredCutout != null && !partialVertexAlpha;
        }

        void observeAlpha(int alpha) {
            if (alpha > 0 && alpha < 255) {
                partialVertexAlpha = true;
            }
        }
    }

    private static final class AlphaTrackingConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final BatchState state;

        AlphaTrackingConsumer(VertexConsumer delegate, BatchState state) {
            this.delegate = delegate;
            this.state = state;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            state.observeAlpha(alpha);
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
        public void defaultColor(int red, int green, int blue, int alpha) {
            state.observeAlpha(alpha);
            delegate.defaultColor(red, green, blue, alpha);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }
}
