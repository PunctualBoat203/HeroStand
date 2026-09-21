package com.herostand.client;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses HeroStand render strategies in a vendor-aware order, but never makes correctness depend
 * on a vendor string. Every backend remains independently usable and a recoverable failure falls
 * through to the remaining backends for the same stand.
 */
final class GpuRenderRouter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int QUARANTINE_AFTER_FAILURES = 2;

    enum Backend {
        NVIDIA_FAST,
        AMD_BALANCED,
        GENERIC_NATIVE
    }

    enum Vendor {
        NVIDIA,
        AMD,
        OTHER
    }

    private final Map<Backend, Integer> consecutiveFailures = new EnumMap<>(Backend.class);

    private boolean initialized;
    private Vendor vendor = Vendor.OTHER;
    private String glVendor = "unknown";
    private String glRenderer = "unknown";
    private String glVersion = "unknown";
    private boolean modernGl = true;

    GpuRenderRouter() {
        for (Backend backend : Backend.values()) {
            consecutiveFailures.put(backend, 0);
        }
    }

    Backend[] orderedBackends() {
        initializeIfNeeded();

        Backend[] preferred = switch (vendor) {
            case NVIDIA -> new Backend[] {
                    Backend.NVIDIA_FAST,
                    Backend.AMD_BALANCED,
                    Backend.GENERIC_NATIVE
            };
            case AMD -> new Backend[] {
                    Backend.AMD_BALANCED,
                    Backend.NVIDIA_FAST,
                    Backend.GENERIC_NATIVE
            };
            case OTHER -> new Backend[] {
                    Backend.GENERIC_NATIVE,
                    Backend.AMD_BALANCED,
                    Backend.NVIDIA_FAST
            };
        };

        // If the driver only exposes an unexpectedly old GL feature set, prefer the path that
        // relies most closely on Minecraft/Palladium's stock renderer.
        if (!modernGl) {
            return new Backend[] {
                    Backend.GENERIC_NATIVE,
                    Backend.AMD_BALANCED,
                    Backend.NVIDIA_FAST
            };
        }

        return preferred;
    }

    boolean isHealthy(Backend backend) {
        return consecutiveFailures.getOrDefault(backend, 0) < QUARANTINE_AFTER_FAILURES;
    }

    void recordSuccess(Backend backend) {
        consecutiveFailures.put(backend, 0);
    }

    void recordSoftFailure(Backend backend, Throwable failure) {
        int next = consecutiveFailures.getOrDefault(backend, 0) + 1;
        consecutiveFailures.put(backend, next);

        if (next == 1) {
            LOGGER.warn(
                    "HeroStand renderer backend {} soft-failed on {} / {}. Falling through to another backend.",
                    backend, glVendor, glRenderer, failure
            );
        } else if (next == QUARANTINE_AFTER_FAILURES) {
            LOGGER.warn(
                    "HeroStand renderer backend {} has soft-failed {} times and is quarantined for this renderer session.",
                    backend, next
            );
        }
    }

    void resetFailures() {
        for (Backend backend : Backend.values()) {
            consecutiveFailures.put(backend, 0);
        }
    }

    Vendor vendor() {
        initializeIfNeeded();
        return vendor;
    }

    String description() {
        initializeIfNeeded();
        return vendor + " | " + glVendor + " | " + glRenderer + " | " + glVersion;
    }

    private void initializeIfNeeded() {
        if (initialized) return;
        initialized = true;

        try {
            String vendorText = GL11.glGetString(GL11.GL_VENDOR);
            String rendererText = GL11.glGetString(GL11.GL_RENDERER);
            String versionText = GL11.glGetString(GL11.GL_VERSION);

            if (vendorText != null && !vendorText.isBlank()) glVendor = vendorText;
            if (rendererText != null && !rendererText.isBlank()) glRenderer = rendererText;
            if (versionText != null && !versionText.isBlank()) glVersion = versionText;

            String haystack = (glVendor + " " + glRenderer).toLowerCase(Locale.ROOT);
            if (haystack.contains("nvidia")) {
                vendor = Vendor.NVIDIA;
            } else if (haystack.contains("amd")
                    || haystack.contains("ati ")
                    || haystack.contains("radeon")
                    || haystack.contains("advanced micro devices")) {
                vendor = Vendor.AMD;
            }

            try {
                modernGl = GL.getCapabilities().OpenGL33;
            } catch (Throwable ignored) {
                modernGl = true;
            }
        } catch (Throwable failure) {
            vendor = Vendor.OTHER;
            modernGl = false;
            LOGGER.warn("HeroStand could not identify the OpenGL driver. Using compatibility-first ordering.", failure);
        }

        LOGGER.info("HeroStand GPU renderer routing: {}", descriptionWithoutInit());
    }

    private String descriptionWithoutInit() {
        return vendor + " | " + glVendor + " | " + glRenderer + " | " + glVersion;
    }
}
