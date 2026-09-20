package com.herostand.registry;

import com.herostand.HeroStand;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, HeroStand.MOD_ID);

    public static final RegistryObject<CreativeModeTab> HERO_STAND = REGISTER.register("hero_stand",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("creativetab.herostand"))
                    .icon(() -> ModItems.HERO_STAND.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(ModItems.HERO_STAND.get()))
                    .build());

    private ModCreativeTabs() {}
}
