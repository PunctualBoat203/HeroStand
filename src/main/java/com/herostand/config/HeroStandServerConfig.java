package com.herostand.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server-authoritative HeroStand limits.
 *
 * Forge SERVER configs live with the world/server and are synchronized to connecting clients,
 * so dedicated server owners can cap client-side HeroStand display cost without requiring a
 * separate networking layer.
 */
public final class HeroStandServerConfig {
    public static final int DEFAULT_MAX_RENDER_DISTANCE = 25;
    public static final int MIN_RENDER_DISTANCE = 1;
    public static final int MAX_RENDER_DISTANCE = 50;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue MAX_SUIT_RENDER_DISTANCE = BUILDER
            .comment(
                    "Server-enforced maximum HeroStand suit render/occlusion distance in blocks.",
                    "Clients may choose a lower personal distance, but never a higher one.",
                    "This SERVER config is synchronized to clients by Forge.",
                    "Default: 25, hard maximum: 50."
            )
            .defineInRange(
                    "maxSuitRenderDistance",
                    DEFAULT_MAX_RENDER_DISTANCE,
                    MIN_RENDER_DISTANCE,
                    MAX_RENDER_DISTANCE
            );

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private HeroStandServerConfig() {}

    public static int maxSuitRenderDistance() {
        int value = MAX_SUIT_RENDER_DISTANCE.get();
        return Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, value));
    }
}
