package com.herostand.client;

import net.minecraft.core.Rotations;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;

/**
 * Creates a reusable client-only Palladium SuitStand when Palladium is installed.
 *
 * The object is never added to the level and never ticks. It exists only so custom Palladium
 * models/layers see the exact entity type and entity data that Palladium's own SuitStandRenderer
 * expects. This is materially safer than asking custom suit code to interpret a plain ArmorStand.
 */
final class PalladiumSuitStandBridge {
    private static final Rotations ZERO_POSE = new Rotations(0.0F, 0.0F, 0.0F);

    private final Constructor<?> suitStandConstructor;
    private boolean healthy;

    private ArmorStand cached;
    private Level cachedLevel;

    PalladiumSuitStandBridge() {
        Constructor<?> constructor = null;
        boolean available = ModList.get().isLoaded("palladium");

        if (available) {
            try {
                Class<?> suitStandClass = Class.forName(
                        "net.threetag.palladium.entity.SuitStand",
                        false,
                        PalladiumSuitStandBridge.class.getClassLoader()
                );
                constructor = suitStandClass.getConstructor(
                        Level.class, double.class, double.class, double.class
                );
            } catch (Throwable ignored) {
                available = false;
            }
        }

        this.suitStandConstructor = constructor;
        this.healthy = available;
    }

    boolean isAvailable() {
        return healthy && suitStandConstructor != null;
    }

    ArmorStand getOrCreate(Level level) {
        if (!isAvailable()) return null;

        if (cached != null && cachedLevel == level) {
            return cached;
        }

        try {
            Object created = suitStandConstructor.newInstance(level, 0.0D, 0.0D, 0.0D);
            if (!(created instanceof ArmorStand stand)) {
                healthy = false;
                return null;
            }

            // Keep Palladium's normal SuitStand scale/Y transform behavior, but hide its physical
            // mannequin/base visual. The entity is only a render context and is never spawned.
            stand.setInvisible(true);
            stand.setNoBasePlate(false);
            stand.setShowArms(true);

            stand.setHeadPose(ZERO_POSE);
            stand.setBodyPose(ZERO_POSE);
            stand.setLeftArmPose(ZERO_POSE);
            stand.setRightArmPose(ZERO_POSE);
            stand.setLeftLegPose(ZERO_POSE);
            stand.setRightLegPose(ZERO_POSE);

            stand.setYRot(0.0F);
            stand.yRotO = 0.0F;
            stand.setYHeadRot(0.0F);
            stand.yHeadRotO = 0.0F;
            stand.yBodyRot = 0.0F;
            stand.yBodyRotO = 0.0F;
            stand.setXRot(0.0F);
            stand.xRotO = 0.0F;

            cached = stand;
            cachedLevel = level;
            return stand;
        } catch (Throwable ignored) {
            healthy = false;
            cached = null;
            cachedLevel = null;
            return null;
        }
    }

    void reset() {
        cached = null;
        cachedLevel = null;
    }
}
