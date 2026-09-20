package com.herostand.client;

import com.herostand.world.HeroStandBlock;
import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;

public final class HeroStandRenderer implements BlockEntityRenderer<HeroStandBlockEntity> {
    public HeroStandRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(HeroStandBlockEntity stand, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = stand.getLevel();
        if (level == null || Minecraft.getInstance().player == null) {
            return;
        }

        boolean hasArmor = false;
        for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
            if (!stand.isEmpty(i)) {
                hasArmor = true;
                break;
            }
        }
        if (!hasArmor) {
            return;
        }

        ArmorStand renderContext = new ArmorStand(level, 0.0D, 0.0D, 0.0D);
        renderContext.setInvisible(true);
        renderContext.setNoBasePlate(true);
        renderContext.setShowArms(true);
        renderContext.setItemSlot(EquipmentSlot.HEAD, stand.getArmor(HeroStandBlockEntity.HEAD));
        renderContext.setItemSlot(EquipmentSlot.CHEST, stand.getArmor(HeroStandBlockEntity.CHEST));
        renderContext.setItemSlot(EquipmentSlot.LEGS, stand.getArmor(HeroStandBlockEntity.LEGS));
        renderContext.setItemSlot(EquipmentSlot.FEET, stand.getArmor(HeroStandBlockEntity.FEET));

        Direction facing = stand.getBlockState().getValue(HeroStandBlock.FACING);
        float yaw = switch (facing) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> 270.0F;
            default -> 0.0F;
        };
        renderContext.setYRot(yaw);
        renderContext.yRotO = yaw;

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        Minecraft.getInstance().getEntityRenderDispatcher().render(
                renderContext, 0.0D, 0.0D, 0.0D, yaw, partialTick, poseStack, buffers, packedLight);
        poseStack.popPose();
    }

    @Override
    public int getViewDistance() {
        return 48;
    }
}
