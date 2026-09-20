package com.herostand.registry;

import com.herostand.HeroStand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> REGISTER =
            DeferredRegister.create(ForgeRegistries.ITEMS, HeroStand.MOD_ID);

    public static final RegistryObject<Item> HERO_STAND = REGISTER.register("hero_stand",
            () -> new BlockItem(ModBlocks.HERO_STAND.get(), new Item.Properties()));

    private ModItems() {}
}
