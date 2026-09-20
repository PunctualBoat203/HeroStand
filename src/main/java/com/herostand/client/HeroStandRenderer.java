package com.herostand.client;

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
    private static final int VIEW_DISTANCE = 21;
    private static final double VIEW_DISTANCE_SQR = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final long OCCLUSION_REFRESH_TICKS = 6L;
    private static final long MOVING_REFRESH_TICKS = 3L;
    private static final double CAMERA_MOVE_REFRESH_SQR = 0.75D * 0.75D;

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
    private ArmorStand renderContext;

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

        prepareRenderContext(stand, level);

        float rotation = stand.getBlockState().getValue(HeroStandBlock.FACING).toYRot();

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.125D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-rotation));

        if (palladium.canUseFastPath(renderContext)) {
            renderPalladiumFastPath(partialTick, poseStack, buffers, packedLight);
        } else {
            Minecraft.getInstance().getEntityRenderDispatcher().render(
                    renderContext, 0.0D, 0.0D, 0.0D, 0.0F,
                    partialTick, poseStack, buffers, packedLight
            );
        }

        poseStack.popPose();
    }

    /**
     * Avoids the full ArmorStand EntityRenderer for Palladium suits. We only reproduce the
     * fixed living-model transform, render the armor layer (where Palladium hooks its custom
     * armor renderer), then render the ArmorRendererData pack layers that Satsu/Palladium use
     * for their extra Gecko geometry and emissive pieces.
     */
    private void renderPalladiumFastPath(float partialTick, PoseStack poseStack,
                                         MultiBufferSource buffers, int packedLight) {
        poseStack.pushPose();

        // Equivalent fixed transform for a normal, non-small ArmorStand with yaw = 0.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0D, -1.501D, 0.0D);

        parentModel.prepareMobModel(renderContext, 0.0F, 0.0F, partialTick);
        parentModel.setupAnim(renderContext, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

        armorLayer.render(
                poseStack, buffers, packedLight, renderContext,
                0.0F, 0.0F, partialTick, 0.0F, 0.0F, 0.0F
        );

        palladium.renderPackLayers(
                renderContext, parentModel, poseStack, buffers, packedLight, partialTick
        );

        poseStack.popPose();
    }

    private void prepareRenderContext(HeroStandBlockEntity stand, Level level) {
        if (renderContext == null || renderContext.level() != level) {
            renderContext = new ArmorStand(level, 0.0D, 0.0D, 0.0D);
            renderContext.setInvisible(true);
            renderContext.setNoBasePlate(true);
            renderContext.setShowArms(true);
        }

        renderContext.setItemSlot(EquipmentSlot.HEAD, stand.getArmor(HeroStandBlockEntity.HEAD));
        renderContext.setItemSlot(EquipmentSlot.CHEST, stand.getArmor(HeroStandBlockEntity.CHEST));
        renderContext.setItemSlot(EquipmentSlot.LEGS, stand.getArmor(HeroStandBlockEntity.LEGS));
        renderContext.setItemSlot(EquipmentSlot.FEET, stand.getArmor(HeroStandBlockEntity.FEET));

        // The context is reused across multiple stands. Pin every current + previous rotation
        // field so interpolation can never carry orientation from the previously rendered stand.
        renderContext.setYRot(0.0F);
        renderContext.yRotO = 0.0F;
        renderContext.setYHeadRot(0.0F);
        renderContext.yHeadRotO = 0.0F;
        renderContext.yBodyRot = 0.0F;
        renderContext.yBodyRotO = 0.0F;
        renderContext.setXRot(0.0F);
        renderContext.xRotO = 0.0F;
    }

    @Override
    public boolean shouldRender(HeroStandBlockEntity stand, Vec3 cameraPos) {
        if (!hasArmor(stand)) return false;

        Vec3 center = Vec3.atCenterOf(stand.getBlockPos());
        if (cameraPos.distanceToSqr(center) > VIEW_DISTANCE_SQR) return false;

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

            if (age < OCCLUSION_REFRESH_TICKS &&
                    (!cameraMovedEnough || age < MOVING_REFRESH_TICKS)) {
                return cached.visible;
            }
        }

        boolean visible = isSuitVisible(level, stand, cameraPos);
        occlusionCache.put(key, new OcclusionEntry(gameTime, cameraPos, visible));

        // Keep this cache bounded if a player has placed/removed many stands over time.
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

    /**
     * Ray checks stop at real occluding blocks. Non-occluding collision shapes (glass, plants,
     * etc.) are skipped so a transparent/non-occluding block does not incorrectly hide a suit.
     */
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

        // Too many non-occluding intersections: favor rendering instead of false-hiding.
        return true;
    }

    private static boolean hasArmor(HeroStandBlockEntity stand) {
        for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
            if (!stand.isEmpty(i)) return true;
        }
        return false;
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    private record OcclusionEntry(long gameTime, Vec3 cameraPos, boolean visible) {}
}
