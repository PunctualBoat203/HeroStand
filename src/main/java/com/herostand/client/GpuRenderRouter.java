package com.herostand.client;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses HeroStand render strategies by actual OpenGL capability first and GPU generation second.
 *
 * The modern path targets NVIDIA Ampere/RTX 30-series and newer, plus AMD RDNA2/RX 6000-series
 * and newer. Correctness never depends on the vendor string: every accelerated backend has two
 * independent fallbacks and repeated soft failures quarantine only the failing backend.
 */
final class GpuRenderRouter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int QUARANTINE_AFTER_FAILURES = 2;

    private static final Pattern NVIDIA_RTX =
            Pattern.compile("\\bRTX\\s*([0-9]{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMD_RX =
            Pattern.compile("\\bRX\\s*([0-9]{4})[A-Z]*\\b", Pattern.CASE_INSENSITIVE);

    enum Backend {
        MODERN_STATIC,
        BALANCED_STREAM,
        GENERIC_NATIVE
    }

    enum Vendor {
        NVIDIA,
        AMD,
        OTHER
    }

    enum HardwareTier {
        MODERN_NVIDIA,
        MODERN_AMD,
        COMPATIBILITY
    }

    private final Map<Backend, Integer> consecutiveFailures = new EnumMap<>(Backend.class);

    private boolean initialized;
    private Vendor vendor = Vendor.OTHER;
    private HardwareTier hardwareTier = HardwareTier.COMPATIBILITY;
    private String glVendor = "unknown";
    private String glRenderer = "unknown";
    private String glVersion = "unknown";

    private boolean openGl45;
    private boolean bufferStorage;
    private boolean multiDrawIndirect;

    GpuRenderRouter() {
        for (Backend backend : Backend.values()) {
            consecutiveFailures.put(backend, 0);
        }
    }

    Backend[] orderedBackends() {
        initializeIfNeeded();

        if (supportsModernStatic()) {
            return new Backend[] {
                    Backend.MODERN_STATIC,
                    Backend.BALANCED_STREAM,
                    Backend.GENERIC_NATIVE
            };
        }

        return new Backend[] {
                Backend.BALANCED_STREAM,
                Backend.GENERIC_NATIVE,
                Backend.MODERN_STATIC
        };
    }

    boolean isSupported(Backend backend) {
        initializeIfNeeded();
        return backend != Backend.MODERN_STATIC || supportsModernStatic();
    }

    boolean supportsModernStatic() {
        initializeIfNeeded();
        return openGl45 && hardwareTier != HardwareTier.COMPATIBILITY;
    }

    int modernMeshLimit() {
        initializeIfNeeded();
        // Keep the first implementation conservative on VRAM. Static suit meshes are small, but
        // each distinct light/equipment variant may own several GL buffers.
        return switch (hardwareTier) {
            case MODERN_NVIDIA -> 128;
            case MODERN_AMD -> 112;
            case COMPATIBILITY -> 64;
        };
    }

    int modernBuildsPerTick() {
        initializeIfNeeded();
        // Upload a few meshes per game tick so a large showroom warms progressively instead of
        // causing one huge first-frame upload spike.
        return switch (hardwareTier) {
            case MODERN_NVIDIA -> 3;
            case MODERN_AMD -> 2;
            case COMPATIBILITY -> 1;
        };
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
                    "HeroStand renderer backend {} soft-failed on {} / {}. Trying the next backend.",
                    backend, glVendor, glRenderer, failure
            );
        } else if (next == QUARANTINE_AFTER_FAILURES) {
            LOGGER.warn(
                    "HeroStand renderer backend {} soft-failed {} times and is quarantined for this renderer session.",
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

    HardwareTier hardwareTier() {
        initializeIfNeeded();
        return hardwareTier;
    }

    String description() {
        initializeIfNeeded();
        return descriptionWithoutInit();
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

            GLCapabilities caps = GL.getCapabilities();
            openGl45 = caps.OpenGL45;
            bufferStorage = caps.OpenGL44 || caps.GL_ARB_buffer_storage;
            multiDrawIndirect = caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect;

            if (vendor == Vendor.NVIDIA && isRtx30OrNewer(glRenderer)) {
                hardwareTier = HardwareTier.MODERN_NVIDIA;
            } else if (vendor == Vendor.AMD && isRx6000OrNewer(glRenderer)) {
                hardwareTier = HardwareTier.MODERN_AMD;
            }
        } catch (Throwable failure) {
            vendor = Vendor.OTHER;
            hardwareTier = HardwareTier.COMPATIBILITY;
            openGl45 = false;
            bufferStorage = false;
            multiDrawIndirect = false;
            LOGGER.warn(
                    "HeroStand could not identify the OpenGL device. Using compatibility rendering.",
                    failure
            );
        }

        LOGGER.info("HeroStand GPU renderer routing: {}", descriptionWithoutInit());
    }

    private static boolean isRtx30OrNewer(String renderer) {
        Matcher matcher = NVIDIA_RTX.matcher(renderer == null ? "" : renderer);
        if (!matcher.find()) return false;

        try {
            int model = Integer.parseInt(matcher.group(1));
            int generation = model / 100;
            return generation >= 30;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isRx6000OrNewer(String renderer) {
        Matcher matcher = AMD_RX.matcher(renderer == null ? "" : renderer);
        if (!matcher.find()) return false;

        try {
            int model = Integer.parseInt(matcher.group(1));
            return model >= 6000;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String descriptionWithoutInit() {
        return hardwareTier
                + " | " + glVendor
                + " | " + glRenderer
                + " | GL " + glVersion
                + " | GL45=" + openGl45
                + " | bufferStorage=" + bufferStorage
                + " | multiDrawIndirect=" + multiDrawIndirect;
    }
}
