package com.herostand.registry;

import com.herostand.HeroStand;
import com.herostand.world.HeroStandBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTER =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, HeroStand.MOD_ID);

    public static final RegistryObject<BlockEntityType<HeroStandBlockEntity>> HERO_STAND =
            REGISTER.register("hero_stand",
                    () -> BlockEntityType.Builder.of(HeroStandBlockEntity::new, ModBlocks.HERO_STAND.get()).build(null));

    private ModBlockEntities() {}
}
