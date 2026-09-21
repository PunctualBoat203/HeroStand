package com.herostand.client;

import com.herostand.HeroStand;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client lifecycle for the 0.2 renderer cache.
 *
 * Cache sweeping is independent from visibility so walking far enough away to stop rendering
 * stands still retires old GPU snapshots.
 */
@Mod.EventBusSubscriber(modid = HeroStand.MOD_ID, value = Dist.CLIENT)
public final class ClientRuntimeEvents {
    private ClientRuntimeEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var level = Minecraft.getInstance().level;
        if (level != null) {
            HeroStandRenderer.tickCaches(level.getGameTime());
        }
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        event.getLeft().add(HeroStandRenderer.debugLine());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        HeroStandRenderer.clearAllCaches();
    }
}
