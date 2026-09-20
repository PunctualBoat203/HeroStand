package com.herostand.client;

import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;

public final class HeroStandRenderer implements BlockEntityRenderer<HeroStandBlockEntity> {
    private ArmorStand renderContext;

    public HeroStandRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(HeroStandBlockEntity stand, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = stand.getLevel();
        if (level == null || Minecraft.getInstance().player == null) return;

        boolean hasArmor = false;
        for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
            if (!stand.isEmpty(i)) { hasArmor = true; break; }
        }
        if (!hasArmor) return;

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

        // Keep the synthetic render entity at one fixed orientation. Rotate the PoseStack instead.
        // This avoids EntityRenderDispatcher interpolating several independent yaw fields and
        // eliminates the visible oscillation/jitter seen with modded suit renderers.
        renderContext.setYRot(0.0F);
        renderContext.yRotO = 0.0F;
        renderContext.setYHeadRot(0.0F);
        renderContext.yHeadRotO = 0.0F;

        float rotation = stand.getBlockState().getValue(com.herostand.world.HeroStandBlock.FACING).toYRot();

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.125D, 0.5D);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-rotation));
        Minecraft.getInstance().getEntityRenderDispatcher().render(
                renderContext, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, poseStack, buffers, packedLight);
        poseStack.popPose();
    }

    @Override public int getViewDistance() { return 48; }
}
