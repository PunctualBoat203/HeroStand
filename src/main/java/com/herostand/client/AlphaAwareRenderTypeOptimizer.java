package com.herostand.client;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces Palladium's blended armor RenderType with vanilla cutout armor rendering only when the
 * texture contains no partial-alpha pixels.
 *
 * Palladium's base armor renderer always writes alpha=1 at the vertex level, so binary texture
 * alpha is sufficient to prove blending is unnecessary here. Unknown/generated textures remain on
 * Palladium's original path.
 */
final class AlphaAwareRenderTypeOptimizer {
    private static final String PALLADIUM_ARMOR =
            "palladium:armor_cutout_no_cull_transparency";
    private static final String ENTITY_TRANSLUCENT = "entity_translucent";

    private final Map<RenderType, RenderType> cache = new IdentityHashMap<>();

    private final Field nameField;
    private final Field stateField;
    private final Field textureStateField;
    private final Method cutoutTextureMethod;

    AlphaAwareRenderTypeOptimizer() {
        Field name = null;
        Field state = null;
        Field textureState = null;
        Method cutout = null;

        try {
            name = RenderStateShard.class.getDeclaredField("name");
            name.setAccessible(true);

            Class<?> compositeType =
                    Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
            state = compositeType.getDeclaredField("state");
            state.setAccessible(true);

            Class<?> compositeState =
                    Class.forName("net.minecraft.client.renderer.RenderType$CompositeState");
            textureState = compositeState.getDeclaredField("textureState");
            textureState.setAccessible(true);

            Class<?> emptyTexture =
                    Class.forName("net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard");
            cutout = emptyTexture.getDeclaredMethod("cutoutTexture");
            cutout.setAccessible(true);
        } catch (Throwable ignored) {
            name = null;
            state = null;
            textureState = null;
            cutout = null;
        }

        this.nameField = name;
        this.stateField = state;
        this.textureStateField = textureState;
        this.cutoutTextureMethod = cutout;
    }

    RenderType optimize(RenderType original) {
        RenderType cached = cache.get(original);
        if (cached != null) return cached;

        RenderType optimized = tryOptimizeAlwaysSafe(original);
        cache.put(original, optimized);
        return optimized;
    }

    /**
     * Returns a cutout replacement for plain entity_translucent only when the texture itself uses
     * binary alpha. The caller must additionally prove the submitted vertex alpha is binary before
     * using this candidate, because Palladium tints may intentionally fade a layer.
     */
    RenderType deferredBinaryCutoutCandidate(RenderType original) {
        if (nameField == null) return null;

        try {
            String name = (String) nameField.get(original);
            if (!ENTITY_TRANSLUCENT.equals(name)) return null;

            Optional<ResourceLocation> texture = textureOf(original);
            if (texture.isEmpty() || !TextureAlphaClassifier.isBinary(texture.get())) {
                return null;
            }

            return RenderType.entityCutoutNoCull(texture.get());
        } catch (Throwable ignored) {
            return null;
        }
    }

    void clear() {
        cache.clear();
        TextureAlphaClassifier.clear();
    }

    private RenderType tryOptimizeAlwaysSafe(RenderType original) {
        if (nameField == null
                || stateField == null
                || textureStateField == null
                || cutoutTextureMethod == null) {
            return original;
        }

        try {
            String name = (String) nameField.get(original);
            if (!PALLADIUM_ARMOR.equals(name)) {
                return original;
            }

            Optional<ResourceLocation> texture = textureOf(original);

            if (texture.isEmpty() || !TextureAlphaClassifier.isBinary(texture.get())) {
                return original;
            }

            /*
             * Vanilla armorCutoutNoCull keeps two-sided armor geometry and the normal armor shader
             * but removes framebuffer blending/sorting when the texture cannot produce partial
             * transparency anyway.
             */
            return RenderType.armorCutoutNoCull(texture.get());
        } catch (Throwable ignored) {
            return original;
        }
    }

    private Optional<ResourceLocation> textureOf(RenderType renderType) throws Exception {
        Object state = stateField.get(renderType);
        Object textureState = textureStateField.get(state);

        @SuppressWarnings("unchecked")
        Optional<ResourceLocation> texture =
                (Optional<ResourceLocation>) cutoutTextureMethod.invoke(textureState);
        return texture;
    }
}
