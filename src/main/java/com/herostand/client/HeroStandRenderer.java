package com.herostand.client;

import com.herostand.config.HeroStandClientConfig;
import com.herostand.config.HeroStandServerConfig;
import com.herostand.world.HeroStandBlock;
import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * HeroStand 0.2 renderer.
 *
 * Ground-up rules:
 * - Palladium owns Palladium visuals. HeroStand does not manually recreate Palladium model math.
 * - Known-static suits are captured from Palladium's real SuitStandRenderer and reused.
 * - Dynamic/translucent/unknown suits stay live on Palladium's renderer.
 * - Non-Palladium armor uses the vanilla ArmorStand renderer.
 * - Proven 0.1.9 distance/wall culling stays independent from visual rendering.
 */
public final class HeroStandRenderer
        implements BlockEntityRenderer<HeroStandBlockEntity> {

    private static final long VISIBLE_REFRESH_TICKS = 8L;
    private static final long OCCLUDED_REFRESH_TICKS = 12L;
    private static final long MOVING_REFRESH_TICKS = 4L;
    private static final double CAMERA_MOVE_REFRESH_SQR =
            0.90D * 0.90D;

    private static final Vec3[] VISIBILITY_SAMPLES = {
            new Vec3(0.50D, 1.20D, 0.50D),
            new Vec3(0.50D, 1.75D, 0.50D),
            new Vec3(0.50D, 0.60D, 0.50D),
            new Vec3(0.22D, 1.20D, 0.50D),
            new Vec3(0.78D, 1.20D, 0.50D),
            new Vec3(0.50D, 1.20D, 0.22D),
            new Vec3(0.50D, 1.20D, 0.78D)
    };

    private static final Set<HeroStandRenderer> ACTIVE_RENDERERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private final PalladiumNativeBridge palladium =
            new PalladiumNativeBridge();
    private final StaticSuitSnapshotCache snapshots =
            new StaticSuitSnapshotCache();

    private final Map<Long, OcclusionEntry> occlusionCache =
            new HashMap<>();

    private Level renderLevel;
    private ArmorStand vanillaFallback;

    public HeroStandRenderer(BlockEntityRendererProvider.Context context) {
        synchronized (ACTIVE_RENDERERS) {
            ACTIVE_RENDERERS.add(this);
        }
    }

    @Override
    public void render(HeroStandBlockEntity stand,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource buffers,
                       int packedLight,
                       int packedOverlay) {
        Level level = stand.getLevel();
        Minecraft minecraft = Minecraft.getInstance();

        if (level == null
                || minecraft.player == null
                || !hasArmor(stand)) {
            return;
        }

        if (renderLevel != level) {
            renderLevel = level;
            clearInstanceCaches();
        }

        float rotation =
                stand.getBlockState()
                        .getValue(HeroStandBlock.FACING)
                        .toYRot();

        poseStack.pushPose();
        try {
            // Keep the 0.1.8/0.1.9 visual placement the user preferred.
            poseStack.translate(0.5D, 0.125D, 0.5D);
            poseStack.mulPose(
                    Axis.YP.rotationDegrees(-rotation));

            if (palladium.isPalladiumSuit(stand)) {
                ArmorStand suitContext =
                        palladium.prepareContext(stand, level);

                if (suitContext != null) {
                    long gameTime = level.getGameTime();

                    // Hot path: once a suit snapshot exists, do not run reflective Palladium
                    // compatibility/model inspection again every frame.
                    if (snapshots.renderCached(
                            suitContext,
                            packedLight,
                            gameTime,
                            poseStack)) {
                        return;
                    }

                    if (palladium.isSnapshotSafe(suitContext)) {
                        boolean snapshotDrawn =
                                snapshots.renderOrBuild(
                                        suitContext,
                                        packedLight,
                                        gameTime,
                                        poseStack,
                                        (localPose, captureSource) ->
                                                minecraft
                                                        .getEntityRenderDispatcher()
                                                        .render(
                                                                suitContext,
                                                                0.0D,
                                                                0.0D,
                                                                0.0D,
                                                                0.0F,
                                                                0.0F,
                                                                localPose,
                                                                captureSource,
                                                                packedLight
                                                        )
                                );

                        if (snapshotDrawn) {
                            return;
                        }
                    }

                    // Dynamic, truly translucent, unknown, or not-yet-cached:
                    // use Palladium's real SuitStandRenderer.
                    minecraft.getEntityRenderDispatcher().render(
                            suitContext,
                            0.0D,
                            0.0D,
                            0.0D,
                            0.0F,
                            partialTick,
                            poseStack,
                            buffers,
                            packedLight
                    );
                    return;
                }
            }

            ArmorStand fallback =
                    prepareVanillaFallback(stand, level);

            minecraft.getEntityRenderDispatcher().render(
                    fallback,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0F,
                    partialTick,
                    poseStack,
                    buffers,
                    packedLight
            );
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public boolean shouldRender(
            HeroStandBlockEntity stand,
            Vec3 cameraPos) {
        if (!hasArmor(stand)) return false;

        int viewDistance = effectiveRenderDistance();
        double viewDistanceSqr =
                (double) viewDistance * viewDistance;

        BlockPos pos = stand.getBlockPos();
        double dx =
                cameraPos.x - (pos.getX() + 0.5D);
        double dy =
                cameraPos.y - (pos.getY() + 0.5D);
        double dz =
                cameraPos.z - (pos.getZ() + 0.5D);

        if (dx * dx + dy * dy + dz * dz
                > viewDistanceSqr) {
            return false;
        }

        Level level = stand.getLevel();
        if (level == null
                || Minecraft.getInstance().player == null) {
            return false;
        }

        if (renderLevel != level) {
            renderLevel = level;
            clearInstanceCaches();
        }

        long gameTime = level.getGameTime();
        long key = pos.asLong();
        OcclusionEntry cached = occlusionCache.get(key);

        if (cached != null) {
            long age = gameTime - cached.gameTime;

            double cameraDx =
                    cameraPos.x - cached.cameraX;
            double cameraDy =
                    cameraPos.y - cached.cameraY;
            double cameraDz =
                    cameraPos.z - cached.cameraZ;

            boolean cameraMovedEnough =
                    cameraDx * cameraDx
                            + cameraDy * cameraDy
                            + cameraDz * cameraDz
                            >= CAMERA_MOVE_REFRESH_SQR;

            long normalRefresh =
                    cached.visible
                            ? VISIBLE_REFRESH_TICKS
                            : OCCLUDED_REFRESH_TICKS;

            if (age < normalRefresh
                    && (!cameraMovedEnough
                    || age < MOVING_REFRESH_TICKS)) {
                return cached.visible;
            }
        }

        boolean visible =
                isSuitVisible(level, stand, cameraPos);

        occlusionCache.put(
                key,
                new OcclusionEntry(
                        gameTime,
                        cameraPos.x,
                        cameraPos.y,
                        cameraPos.z,
                        visible
                )
        );

        if (occlusionCache.size() > 2048) {
            occlusionCache.clear();
        }

        return visible;
    }

    @Override
    public int getViewDistance() {
        return effectiveRenderDistance();
    }

    static void tickCaches(long gameTime) {
        synchronized (ACTIVE_RENDERERS) {
            for (HeroStandRenderer renderer : ACTIVE_RENDERERS) {
                renderer.snapshots.tick(gameTime);
            }
        }
    }

    static void clearAllCaches() {
        Runnable clear = () -> {
            synchronized (ACTIVE_RENDERERS) {
                for (HeroStandRenderer renderer : ACTIVE_RENDERERS) {
                    renderer.clearInstanceCaches();
                }
            }
        };

        if (RenderSystem.isOnRenderThread()) {
            clear.run();
        } else {
            RenderSystem.recordRenderCall(clear::run);
        }
    }

    private void clearInstanceCaches() {
        snapshots.clear();
        palladium.reset();
        occlusionCache.clear();
        vanillaFallback = null;
    }

    private ArmorStand prepareVanillaFallback(
            HeroStandBlockEntity stand,
            Level level) {
        if (vanillaFallback == null
                || vanillaFallback.level() != level) {
            vanillaFallback =
                    new ArmorStand(
                            level, 0.0D, 0.0D, 0.0D);

            vanillaFallback.setInvisible(true);
            vanillaFallback.setNoBasePlate(true);
            vanillaFallback.setShowArms(true);
        }

        vanillaFallback.setItemSlot(
                EquipmentSlot.HEAD,
                stand.getArmor(HeroStandBlockEntity.HEAD));
        vanillaFallback.setItemSlot(
                EquipmentSlot.CHEST,
                stand.getArmor(HeroStandBlockEntity.CHEST));
        vanillaFallback.setItemSlot(
                EquipmentSlot.LEGS,
                stand.getArmor(HeroStandBlockEntity.LEGS));
        vanillaFallback.setItemSlot(
                EquipmentSlot.FEET,
                stand.getArmor(HeroStandBlockEntity.FEET));

        pinRotation(vanillaFallback);
        return vanillaFallback;
    }

    private boolean isSuitVisible(Level level,
                                  HeroStandBlockEntity stand,
                                  Vec3 cameraPos) {
        BlockPos pos = stand.getBlockPos();

        for (Vec3 sample : VISIBILITY_SAMPLES) {
            Vec3 target =
                    new Vec3(
                            pos.getX() + sample.x,
                            pos.getY() + sample.y,
                            pos.getZ() + sample.z
                    );

            if (hasClearLine(
                    level,
                    stand,
                    cameraPos,
                    target)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasClearLine(Level level,
                                 HeroStandBlockEntity stand,
                                 Vec3 from,
                                 Vec3 to) {
        Vec3 start = from;
        Vec3 direction = to.subtract(from);
        double length = direction.length();

        if (length < 1.0E-4D) return true;

        direction = direction.scale(1.0D / length);

        for (int pass = 0; pass < 8; pass++) {
            BlockHitResult hit =
                    level.clip(
                            new ClipContext(
                                    start,
                                    to,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    Minecraft.getInstance().player
                            )
                    );

            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }

            if (hit.getBlockPos()
                    .equals(stand.getBlockPos())) {
                return true;
            }

            BlockState hitState =
                    level.getBlockState(hit.getBlockPos());

            if (hitState.canOcclude()) {
                return false;
            }

            Vec3 next =
                    hit.getLocation()
                            .add(direction.scale(0.05D));

            if (next.distanceToSqr(to) < 0.01D) {
                return true;
            }

            if (next.distanceToSqr(start)
                    < 1.0E-6D) {
                next =
                        start.add(
                                direction.scale(0.05D));
            }

            start = next;
        }

        return true;
    }

    private static void pinRotation(ArmorStand context) {
        context.setYRot(0.0F);
        context.yRotO = 0.0F;
        context.setYHeadRot(0.0F);
        context.yHeadRotO = 0.0F;
        context.yBodyRot = 0.0F;
        context.yBodyRotO = 0.0F;
        context.setXRot(0.0F);
        context.xRotO = 0.0F;
    }

    private static int effectiveRenderDistance() {
        return Math.min(
                HeroStandClientConfig.renderDistance(),
                HeroStandServerConfig
                        .maxSuitRenderDistance()
        );
    }

    private static boolean hasArmor(
            HeroStandBlockEntity stand) {
        for (int i = 0;
             i < HeroStandBlockEntity.SLOT_COUNT;
             i++) {
            if (!stand.isEmpty(i)) return true;
        }

        return false;
    }

    private record OcclusionEntry(
            long gameTime,
            double cameraX,
            double cameraY,
            double cameraZ,
            boolean visible
    ) {}
}
