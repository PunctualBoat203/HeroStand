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
 * One-time alpha classification for static texture resources.
 *
 * Binary alpha (0/255 only) can safely use cutout rendering. Any partial alpha remains on the
 * original translucent path. Generated/dynamic textures that are not backed by the resource
 * manager stay UNKNOWN and are rendered live instead of guessed.
 */
final class TextureAlphaClassifier {
    enum AlphaMode {
        BINARY,
        TRANSLUCENT,
        UNKNOWN
    }

    private final Map<ResourceLocation, AlphaMode> cache = new HashMap<>();

    AlphaMode classify(ResourceLocation texture) {
        AlphaMode cached = cache.get(texture);
        if (cached != null) return cached;

        AlphaMode mode = inspect(texture);
        cache.put(texture, mode);
        return mode;
    }

    void clear() {
        cache.clear();
    }

    private static AlphaMode inspect(ResourceLocation texture) {
        try {
            Optional<Resource> resource =
                    Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isEmpty()) return AlphaMode.UNKNOWN;

            try (InputStream input = resource.get().open();
                 NativeImage image = NativeImage.read(input)) {
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int alpha = (image.getPixelRGBA(x, y) >>> 24) & 0xFF;
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
