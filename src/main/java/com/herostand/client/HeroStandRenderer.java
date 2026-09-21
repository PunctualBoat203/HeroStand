package com.herostand.client;

import com.herostand.config.HeroStandClientConfig;
import com.herostand.config.HeroStandServerConfig;
import com.herostand.world.HeroStandBlock;
import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmorStandArmorModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.core.Rotations;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class HeroStandRenderer implements BlockEntityRenderer<HeroStandBlockEntity> {
    private static final long VISIBLE_REFRESH_TICKS = 8L;
    private static final long OCCLUDED_REFRESH_TICKS = 12L;
    private static final long MOVING_REFRESH_TICKS = 4L;
    private static final double CAMERA_MOVE_REFRESH_SQR = 0.90D * 0.90D;

    // Palladium's SuitStandRenderer applies these exact values.
    private static final float PALLADIUM_SUIT_SCALE = 0.9375F;
    private static final double PALLADIUM_SUIT_Y_OFFSET = -0.0625D;
    private static final Rotations ZERO_POSE = new Rotations(0.0F, 0.0F, 0.0F);

    private static final Vec3[] VISIBILITY_SAMPLES = {
            new Vec3(0.50D, 1.20D, 0.50D), // chest: cheapest/common clear ray first
            new Vec3(0.50D, 1.75D, 0.50D), // head
            new Vec3(0.50D, 0.60D, 0.50D), // legs
            new Vec3(0.22D, 1.20D, 0.50D), // left side
            new Vec3(0.78D, 1.20D, 0.50D), // right side
            new Vec3(0.50D, 1.20D, 0.22D), // front/back partial visibility
            new Vec3(0.50D, 1.20D, 0.78D)
    };

    private final ArmorStandArmorModel parentModel;
    private final HumanoidArmorLayer<ArmorStand, ArmorStandArmorModel, ArmorStandArmorModel> armorLayer;
    private final PalladiumRenderBridge palladium = new PalladiumRenderBridge();

    private final Map<Long, OcclusionEntry> occlusionCache = new HashMap<>();
    private Level occlusionLevel;

    private ArmorStand fastRenderContext;
    private ArmorStand fallbackRenderContext;

    public HeroStandRenderer(BlockEntityRendererProvider.Context context) {
        this.parentModel = new ArmorStandArmorModel(context.bakeLayer(ModelLayers.ARMOR_STAND));

        RenderLayerParent<ArmorStand, ArmorStandArmorModel> parent =
                new RenderLayerParent<>() {
                    @Override
                    public ArmorStandArmorModel getModel() {
                        return parentModel;
                    }

                    @Override
                    public ResourceLocation getTextureLocation(ArmorStand entity) {
                        return ArmorStandRenderer.DEFAULT_SKIN_LOCATION;
                    }
                };

        this.armorLayer = new HumanoidArmorLayer<>(
                parent,
                new ArmorStandArmorModel(context.bakeLayer(ModelLayers.ARMOR_STAND_INNER_ARMOR)),
                new ArmorStandArmorModel(context.bakeLayer(ModelLayers.ARMOR_STAND_OUTER_ARMOR)),
                Minecraft.getInstance().getModelManager()
        );
    }

    @Override
    public void render(HeroStandBlockEntity stand, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = stand.getLevel();
        if (level == null || Minecraft.getInstance().player == null || !hasArmor(stand)) return;

        boolean fastPath = palladium.canUseFastPath(stand);
        ArmorStand renderContext = fastPath
                ? prepareFastRenderContext(stand, level)
                : prepareFallbackRenderContext(stand, level);

        float rotation = stand.getBlockState().getValue(HeroStandBlock.FACING).toYRot();

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.125D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-rotation));

        if (fastPath) {
            renderPalladiumFastPath(renderContext, partialTick, poseStack, buffers, packedLight);
        } else {
            Minecraft.getInstance().getEntityRenderDispatcher().render(
                    renderContext, 0.0D, 0.0D, 0.0D, 0.0F,
                    partialTick, poseStack, buffers, packedLight
            );
        }

        poseStack.popPose();
    }

    /**
     * Mirrors Palladium's SuitStandRenderer transform but skips the full living-entity renderer.
     * The static neutral pose is prepared once when the reusable fast render context is created.
     */
    private void renderPalladiumFastPath(ArmorStand renderContext, float partialTick,
                                         PoseStack poseStack, MultiBufferSource buffers,
                                         int packedLight) {
        poseStack.pushPose();

        // SuitStandRenderer.setupRotations(...), scale(...), then LivingEntityRenderer's
        // model-space flip/translation.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.scale(PALLADIUM_SUIT_SCALE, PALLADIUM_SUIT_SCALE, PALLADIUM_SUIT_SCALE);
        poseStack.translate(0.0D, PALLADIUM_SUIT_Y_OFFSET, 0.0D);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0D, -1.501D, 0.0D);

        armorLayer.render(
                poseStack, buffers, packedLight, renderContext,
                0.0F, 0.0F, partialTick, 0.0F, 0.0F, 0.0F
        );

        palladium.renderPackLayers(
                renderContext, parentModel, poseStack, buffers, packedLight, partialTick
        );

        poseStack.popPose();
    }

    private ArmorStand prepareFastRenderContext(HeroStandBlockEntity stand, Level level) {
        if (fastRenderContext == null || fastRenderContext.level() != level) {
            fastRenderContext = createBaseContext(level);

            // Palladium's SuitStand constructor explicitly zeros arm/leg poses. Do the same,
            // including head/body, so ArmorStandArmorModel copies a stable neutral mannequin pose.
            fastRenderContext.setHeadPose(ZERO_POSE);
            fastRenderContext.setBodyPose(ZERO_POSE);
            fastRenderContext.setLeftArmPose(ZERO_POSE);
            fastRenderContext.setRightArmPose(ZERO_POSE);
            fastRenderContext.setLeftLegPose(ZERO_POSE);
            fastRenderContext.setRightLegPose(ZERO_POSE);

            // This model is static for every HeroStand fast-path render. Preparing it once avoids
            // repeating ArmorStand pose setup for every visible stand on every frame.
            parentModel.prepareMobModel(fastRenderContext, 0.0F, 0.0F, 0.0F);
            parentModel.setupAnim(fastRenderContext, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

            palladium.resetSessionCache();
        }

        copyEquipment(stand, fastRenderContext);
        pinRotationState(fastRenderContext);
        return fastRenderContext;
    }

    private ArmorStand prepareFallbackRenderContext(HeroStandBlockEntity stand, Level level) {
        if (fallbackRenderContext == null || fallbackRenderContext.level() != level) {
            fallbackRenderContext = createBaseContext(level);
        }

        copyEquipment(stand, fallbackRenderContext);
        pinRotationState(fallbackRenderContext);
        return fallbackRenderContext;
    }

    private static ArmorStand createBaseContext(Level level) {
        ArmorStand context = new ArmorStand(level, 0.0D, 0.0D, 0.0D);
        context.setInvisible(true);
        context.setNoBasePlate(true);
        context.setShowArms(true);
        return context;
    }

    private static void copyEquipment(HeroStandBlockEntity stand, ArmorStand context) {
        context.setItemSlot(EquipmentSlot.HEAD, stand.getArmor(HeroStandBlockEntity.HEAD));
        context.setItemSlot(EquipmentSlot.CHEST, stand.getArmor(HeroStandBlockEntity.CHEST));
        context.setItemSlot(EquipmentSlot.LEGS, stand.getArmor(HeroStandBlockEntity.LEGS));
        context.setItemSlot(EquipmentSlot.FEET, stand.getArmor(HeroStandBlockEntity.FEET));
    }

    private static void pinRotationState(ArmorStand context) {
        context.setYRot(0.0F);
        context.yRotO = 0.0F;
        context.setYHeadRot(0.0F);
        context.yHeadRotO = 0.0F;
        context.yBodyRot = 0.0F;
        context.yBodyRotO = 0.0F;
        context.setXRot(0.0F);
        context.xRotO = 0.0F;
    }

    @Override
    public boolean shouldRender(HeroStandBlockEntity stand, Vec3 cameraPos) {
        if (!hasArmor(stand)) return false;

        int viewDistance = effectiveRenderDistance();
        double viewDistanceSqr = (double) viewDistance * viewDistance;
        Vec3 center = Vec3.atCenterOf(stand.getBlockPos());
        if (cameraPos.distanceToSqr(center) > viewDistanceSqr) return false;

        Level level = stand.getLevel();
        if (level == null || Minecraft.getInstance().player == null) return false;

        if (level != occlusionLevel) {
            occlusionLevel = level;
            occlusionCache.clear();
        }

        long gameTime = level.getGameTime();
        long key = stand.getBlockPos().asLong();
        OcclusionEntry cached = occlusionCache.get(key);

        if (cached != null) {
            long age = gameTime - cached.gameTime;
            double cameraMove = cameraPos.distanceToSqr(cached.cameraPos);
            boolean cameraMovedEnough = cameraMove >= CAMERA_MOVE_REFRESH_SQR;
            long normalRefresh = cached.visible ? VISIBLE_REFRESH_TICKS : OCCLUDED_REFRESH_TICKS;

            if (age < normalRefresh &&
                    (!cameraMovedEnough || age < MOVING_REFRESH_TICKS)) {
                return cached.visible;
            }
        }

        boolean visible = isSuitVisible(level, stand, cameraPos);
        occlusionCache.put(key, new OcclusionEntry(gameTime, cameraPos, visible));

        if (occlusionCache.size() > 2048) occlusionCache.clear();
        return visible;
    }

    private boolean isSuitVisible(Level level, HeroStandBlockEntity stand, Vec3 cameraPos) {
        for (Vec3 sample : VISIBILITY_SAMPLES) {
            Vec3 target = new Vec3(
                    stand.getBlockPos().getX() + sample.x,
                    stand.getBlockPos().getY() + sample.y,
                    stand.getBlockPos().getZ() + sample.z
            );
            if (hasClearLine(level, stand, cameraPos, target)) return true;
        }
        return false;
    }

    private boolean hasClearLine(Level level, HeroStandBlockEntity stand, Vec3 from, Vec3 to) {
        Vec3 start = from;
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        if (length < 1.0E-4D) return true;
        direction = direction.scale(1.0D / length);

        for (int pass = 0; pass < 8; pass++) {
            BlockHitResult hit = level.clip(new ClipContext(
                    start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    Minecraft.getInstance().player
            ));

            if (hit.getType() == HitResult.Type.MISS) return true;
            if (hit.getBlockPos().equals(stand.getBlockPos())) return true;

            BlockState hitState = level.getBlockState(hit.getBlockPos());
            if (hitState.canOcclude()) return false;

            Vec3 next = hit.getLocation().add(direction.scale(0.05D));
            if (next.distanceToSqr(to) < 0.01D) return true;
            if (next.distanceToSqr(start) < 1.0E-6D) {
                next = start.add(direction.scale(0.05D));
            }
            start = next;
        }

        return true;
    }

    private static int effectiveRenderDistance() {
        return Math.min(
                HeroStandClientConfig.renderDistance(),
                HeroStandServerConfig.maxSuitRenderDistance()
        );
    }

    private static boolean hasArmor(HeroStandBlockEntity stand) {
        for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
            if (!stand.isEmpty(i)) return true;
        }
        return false;
    }

    @Override
    public int getViewDistance() {
        return effectiveRenderDistance();
    }

    private record OcclusionEntry(long gameTime, Vec3 cameraPos, boolean visible) {}
}
