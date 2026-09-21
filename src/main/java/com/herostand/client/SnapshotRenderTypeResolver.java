package com.herostand.client;

import net.minecraft.client.renderer.RenderType;

/**
 * Stable RenderType policy for cached Palladium base armor.
 *
 * Do not reflect private Minecraft field names here. In the user's Forge/SRG runtime the old
 * sortOnUpload reflection could fail and falsely classify Palladium's base armor as sorted.
 */
final class SnapshotRenderTypeResolver {
    private static final String PALLADIUM_ARMOR =
            "palladium:armor_cutout_no_cull_transparency";

    RenderType resolve(RenderType original, boolean partialVertexAlpha) {
        return original;
    }

    boolean requiresSorting(RenderType renderType) {
        String name = renderType.toString().toLowerCase(java.util.Locale.ROOT);

        // Palladium creates this RenderType with sortOnUpload=false.
        if (name.contains(PALLADIUM_ARMOR)) {
            return false;
        }

        // HeroStand 0.2.6 snapshots only the native HumanoidArmorLayer. Glint/cutout armor
        // RenderTypes are not camera-sorted. Anything explicitly translucent/effect-like stays live.
        if (name.contains("translucent")
                || name.contains("glowing")
                || name.contains("energy_swirl")
                || name.contains("laser")
                || name.contains("lightning")
                || name.contains("beacon_beam")) {
            return true;
        }

        return false;
    }

    void clear() {
    }
}
