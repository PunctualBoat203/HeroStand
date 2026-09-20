package com.herostand.registry;

import com.herostand.HeroStand;
import com.herostand.world.HeroStandBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> REGISTER =
            DeferredRegister.create(ForgeRegistries.BLOCKS, HeroStand.MOD_ID);

    public static final RegistryObject<Block> HERO_STAND = REGISTER.register("hero_stand",
            () -> new HeroStandBlock(BlockBehaviour.Properties.of()
                    .strength(4.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    private ModBlocks() {}
}
