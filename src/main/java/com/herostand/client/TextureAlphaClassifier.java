package com.herostand.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One-time alpha classification for suit textures.
 *
 * First try normal resource-pack bytes. Palladium can also generate/register textures at runtime,
 * so when no ResourceManager resource exists, 0.2.4 falls back to reading mip 0 from the already
 * uploaded OpenGL texture. That lets static generated superhero textures prove they are binary
 * alpha instead of being rejected merely because their ResourceLocation is runtime-only.
 */
final class TextureAlphaClassifier {
    enum AlphaMode {
        BINARY,
        TRANSLUCENT,
        UNKNOWN
    }

    private static final long MAX_READBACK_BYTES = 32L * 1024L * 1024L;

    private final Map<ResourceLocation, AlphaMode> cache = new HashMap<>();

    AlphaMode classify(ResourceLocation texture) {
        AlphaMode cached = cache.get(texture);
        if (cached != null) return cached;

        AlphaMode mode = inspectResource(texture);
        if (mode == AlphaMode.UNKNOWN) {
            mode = inspectUploadedTexture(texture);
        }

        // UNKNOWN often means Palladium has registered the dynamic texture but its deferred upload
        // has not run yet. Do not poison the cache permanently; a later snapshot attempt may succeed.
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

    private static AlphaMode inspectUploadedTexture(ResourceLocation texture) {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            return AlphaMode.UNKNOWN;
        }

        AbstractTexture abstractTexture =
                Minecraft.getInstance()
                        .getTextureManager()
                        .getTexture(texture, null);

        if (abstractTexture == null) {
            return AlphaMode.UNKNOWN;
        }

        int previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            GlStateManager._bindTexture(abstractTexture.getId());

            int width = GL11.glGetTexLevelParameteri(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_TEXTURE_WIDTH
            );
            int height = GL11.glGetTexLevelParameteri(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_TEXTURE_HEIGHT
            );

            if (width <= 0 || height <= 0) {
                return AlphaMode.UNKNOWN;
            }

            long byteCount = (long) width * (long) height * 4L;
            if (byteCount <= 0L || byteCount > MAX_READBACK_BYTES) {
                return AlphaMode.UNKNOWN;
            }

            ByteBuffer pixels = BufferUtils.createByteBuffer((int) byteCount);
            GL11.glGetTexImage(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    pixels
            );

            for (int i = 3; i < byteCount; i += 4) {
                int alpha = pixels.get(i) & 0xFF;
                if (alpha != 0 && alpha != 255) {
                    return AlphaMode.TRANSLUCENT;
                }
            }

            return AlphaMode.BINARY;
        } catch (Throwable ignored) {
            return AlphaMode.UNKNOWN;
        } finally {
            GlStateManager._bindTexture(previousBinding);
        }
    }
}
