package com.herostand.world;

import com.herostand.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class HeroStandBlockEntity extends BlockEntity {
    public static final int HEAD = 0;
    public static final int CHEST = 1;
    public static final int LEGS = 2;
    public static final int FEET = 3;
    public static final int SLOT_COUNT = 4;

    private final NonNullList<ItemStack> armor = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    public HeroStandBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HERO_STAND.get(), pos, state);
    }

    public ItemStack getArmor(int slot) {
        return armor.get(slot);
    }

    public boolean isEmpty(int slot) {
        return armor.get(slot).isEmpty();
    }

    public void setArmor(int slot, ItemStack stack) {
        armor.set(slot, stack);
        sync();
    }

    public ItemStack removeArmor(int slot) {
        ItemStack result = armor.get(slot);
        armor.set(slot, ItemStack.EMPTY);
        sync();
        return result;
    }

    public NonNullList<ItemStack> armor() {
        return armor;
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, armor);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        armor.clear();
        ContainerHelper.loadAllItems(tag, armor);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
        }
    }
}
