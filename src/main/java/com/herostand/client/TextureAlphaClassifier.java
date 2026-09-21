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
 * Safe one-time alpha classification for ordinary resource-pack textures.
 *
 * IMPORTANT: never bind/read OpenGL textures from this class. The 0.2.4 runtime glGetTexImage
 * experiment disturbed Minecraft's active texture state during the world pass and produced the
 * black/flickering screen artifact reported by PunctualBoat.
 *
 * Palladium runtime/generated textures simply remain UNKNOWN here. 0.2.5 can still snapshot their
 * translucent geometry because it keeps Minecraft-style SortState and updates only the index order
 * for the current camera before drawing.
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

        AlphaMode mode = inspectResource(texture);

        // Cache definitive resource results. UNKNOWN may become resolvable after a resource reload.
        if (mode != AlphaMode.UNKNOWN) {
            cache.put(texture, mode);
        }

        return mode;
    }

    void clear() {
        cache.clear();
    }

    private static AlphaMode inspectResource(ResourceLocation texture) {
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
