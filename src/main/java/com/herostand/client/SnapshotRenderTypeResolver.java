package com.herostand.client;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Chooses a snapshot-safe draw RenderType.
 *
 * Palladium's base armor and its nominal "solid" pack layer can use blended translucent
 * RenderTypes. If both the texture alpha and emitted vertex alpha prove the geometry is binary,
 * HeroStand can use vanilla cutout/no-cull rendering instead. True translucency is never cached in
 * the 0.2 snapshot path because camera-relative translucent sorting would become stale.
 */
final class SnapshotRenderTypeResolver {
    private static final String PALLADIUM_ARMOR =
            "palladium:armor_cutout_no_cull_transparency";
    private static final String ENTITY_TRANSLUCENT = "entity_translucent";

    private final TextureAlphaClassifier alphaClassifier = new TextureAlphaClassifier();

    private final Field nameField;
    private final Field stateField;
    private final Field textureStateField;
    private final Method cutoutTextureMethod;

    SnapshotRenderTypeResolver() {
        Field name = null;
        Field state = null;
        Field texture = null;
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
            texture = compositeState.getDeclaredField("textureState");
            texture.setAccessible(true);

            Class<?> emptyTexture =
                    Class.forName("net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard");
            cutout = emptyTexture.getDeclaredMethod("cutoutTexture");
            cutout.setAccessible(true);
        } catch (Throwable ignored) {
        }

        this.nameField = name;
        this.stateField = state;
        this.textureStateField = texture;
        this.cutoutTextureMethod = cutout;
    }

    RenderType resolve(RenderType original, boolean partialVertexAlpha) {
        if (partialVertexAlpha) return original;
        if (nameField == null || stateField == null
                || textureStateField == null || cutoutTextureMethod == null) {
            return original;
        }

        try {
            String name = (String) nameField.get(original);
            if (!PALLADIUM_ARMOR.equals(name) && !ENTITY_TRANSLUCENT.equals(name)) {
                return original;
            }

            Optional<ResourceLocation> texture = textureOf(original);
            if (texture.isEmpty()
                    || alphaClassifier.classify(texture.get())
                    != TextureAlphaClassifier.AlphaMode.BINARY) {
                return original;
            }

            if (PALLADIUM_ARMOR.equals(name)) {
                return RenderType.armorCutoutNoCull(texture.get());
            }

            return RenderType.entityCutoutNoCull(texture.get());
        } catch (Throwable ignored) {
            return original;
        }
    }

    void clear() {
        alphaClassifier.clear();
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
