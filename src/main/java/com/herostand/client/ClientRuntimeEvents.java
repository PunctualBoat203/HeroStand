package com.herostand.client;

import com.herostand.HeroStand;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Runtime lifecycle for HeroStand GPU resources.
 *
 * These hooks are deliberately independent from whether any stand is currently visible. That lets
 * idle VBOs retire while the player is far away and guarantees disconnects purge GPU resources.
 */
@Mod.EventBusSubscriber(modid = HeroStand.MOD_ID, value = Dist.CLIENT)
public final class ClientRuntimeEvents {
    private ClientRuntimeEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var level = Minecraft.getInstance().level;
        if (level != null) {
            HeroStandRenderer.tickGpuCaches(level.getGameTime());
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            HeroStandRenderer.flushSharedBatch();
        }
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        HeroStandRenderer.clearAllGpuCaches();
    }
}
