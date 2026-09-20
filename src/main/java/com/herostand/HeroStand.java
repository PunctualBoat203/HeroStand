package com.herostand;

import com.herostand.registry.ModBlockEntities;
import com.herostand.registry.ModBlocks;
import com.herostand.registry.ModCreativeTabs;
import com.herostand.registry.ModItems;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(HeroStand.MOD_ID)
public final class HeroStand {
    public static final String MOD_ID = "herostand";

    public HeroStand() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.REGISTER.register(bus);
        ModItems.REGISTER.register(bus);
        ModBlockEntities.REGISTER.register(bus);
        ModCreativeTabs.REGISTER.register(bus);
    }
}
