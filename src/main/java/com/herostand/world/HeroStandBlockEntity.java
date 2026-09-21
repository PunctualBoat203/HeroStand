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
    public static final int HEAD=0, CHEST=1, LEGS=2, FEET=3, SLOT_COUNT=4;
    private final NonNullList<ItemStack> armor = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private int renderRevision;

    public HeroStandBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.HERO_STAND.get(), pos, state); }
    public ItemStack getArmor(int slot) { return armor.get(slot); }
    public boolean isEmpty(int slot) { return armor.get(slot).isEmpty(); }
    public NonNullList<ItemStack> armor() { return armor; }
    public int renderRevision() { return renderRevision; }

    public void setArmor(int slot, ItemStack stack) {
        armor.set(slot, stack.copyWithCount(1));
        renderRevision++;
        sync();
    }

    public ItemStack removeArmor(int slot) {
        ItemStack result = armor.get(slot);
        armor.set(slot, ItemStack.EMPTY);
        renderRevision++;
        sync();
        return result;
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, armor);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        for (int i=0;i<SLOT_COUNT;i++) armor.set(i, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, armor);
        renderRevision++;
    }

    @Override public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag=packet.getTag();
        if (tag!=null) load(tag);
    }
}
