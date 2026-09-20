package com.herostand.world;

import com.herostand.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class HeroStandBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public HeroStandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModBlockEntities.HERO_STAND.get().create(pos, state);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        BlockEntity raw = level.getBlockEntity(pos);
        if (!(raw instanceof HeroStandBlockEntity stand)) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof ArmorItem armorItem) {
            int slot = slotIndex(armorItem.getEquipmentSlot());
            if (slot >= 0 && stand.isEmpty(slot)) {
                if (!level.isClientSide) {
                    ItemStack display = held.copyWithCount(1);
                    stand.setArmor(slot, display);
                    if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                    }
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }

        if (held.isEmpty()) {
            int slot = pickRemovalSlot(stand);
            if (slot >= 0) {
                if (!level.isClientSide) {
                    ItemStack removed = stand.removeArmor(slot);
                    if (!player.addItem(removed)) {
                        player.drop(removed, false);
                    }
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }

        return InteractionResult.PASS;
    }

    private static int pickRemovalSlot(HeroStandBlockEntity stand) {
        for (int slot = HeroStandBlockEntity.HEAD; slot < HeroStandBlockEntity.SLOT_COUNT; slot++) {
            if (!stand.isEmpty(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static int slotIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> HeroStandBlockEntity.HEAD;
            case CHEST -> HeroStandBlockEntity.CHEST;
            case LEGS -> HeroStandBlockEntity.LEGS;
            case FEET -> HeroStandBlockEntity.FEET;
            default -> -1;
        };
    }

    @Override
    public void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!oldState.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof HeroStandBlockEntity stand) {
            Containers.dropContents(level, pos, stand.armor());
        }
        super.onRemove(oldState, level, pos, newState, moving);
    }
}
