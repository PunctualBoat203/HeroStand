package com.herostand.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class HeroStandClientConfig {
    public static final int DEFAULT_RENDER_DISTANCE = 25;
    public static final int MIN_RENDER_DISTANCE = 1;
    public static final int MAX_RENDER_DISTANCE = 50;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue RENDER_DISTANCE = BUILDER
            .comment(
                    "Maximum distance in blocks at which equipped HeroStand suits are considered for rendering.",
                    "Occlusion/frustum checks still apply inside this distance.",
                    "Default: 25, maximum: 50."
            )
            .defineInRange(
                    "renderDistance",
                    DEFAULT_RENDER_DISTANCE,
                    MIN_RENDER_DISTANCE,
                    MAX_RENDER_DISTANCE
            );

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private HeroStandClientConfig() {}

    public static int renderDistance() {
        int value = RENDER_DISTANCE.get();
        return Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, value));
    }
}
