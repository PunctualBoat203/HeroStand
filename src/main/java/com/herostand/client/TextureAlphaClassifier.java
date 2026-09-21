package com.herostand.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads static texture alpha once and remembers whether blending is actually required.
 *
 * Dynamic/generated textures that are not backed by a ResourceManager resource remain UNKNOWN and
 * keep Palladium's original translucent path. This deliberately favors correctness over guessing.
 */
final class TextureAlphaClassifier {
    enum AlphaMode {
        BINARY,
        TRANSLUCENT,
        UNKNOWN
    }

    private static final Map<ResourceLocation, AlphaMode> CACHE = new HashMap<>();

    private TextureAlphaClassifier() {}

    static AlphaMode classify(ResourceLocation texture) {
        AlphaMode cached = CACHE.get(texture);
        if (cached != null) return cached;

        AlphaMode mode = inspect(texture);
        CACHE.put(texture, mode);
        return mode;
    }

    static boolean isBinary(ResourceLocation texture) {
        return classify(texture) == AlphaMode.BINARY;
    }

    static void clear() {
        CACHE.clear();
    }

    private static AlphaMode inspect(ResourceLocation texture) {
        try {
            Optional<Resource> resource =
                    Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return AlphaMode.UNKNOWN;
            }

            try (InputStream input = resource.get().open();
                 NativeImage image = NativeImage.read(input)) {
                int width = image.getWidth();
                int height = image.getHeight();

                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int rgba = image.getPixelRGBA(x, y);
                        int alpha = (rgba >>> 24) & 0xFF;

                        if (alpha != 0 && alpha != 255) {
                            return AlphaMode.TRANSLUCENT;
                        }
                    }
                }

                return AlphaMode.BINARY;
            }
        } catch (Throwable ignored) {
            return AlphaMode.UNKNOWN;
        }
    }
}
