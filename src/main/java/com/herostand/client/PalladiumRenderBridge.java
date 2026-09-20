package com.herostand.client;

import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional Palladium integration. All Palladium access is reflective so HeroStand still loads
 * normally when Palladium is absent.
 */
final class PalladiumRenderBridge {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final boolean installed;
    private boolean healthy;

    private final Class<?> armorWithRendererClass;
    private final Class<?> armorRendererDataClass;
    private final Method getCachedArmorRenderer;
    private final Method getRenderLayers;
    private final Method forArmorInSlot;
    private final Method renderLayer;

    /**
     * Identity cache is intentional: Minecraft Items are registry singletons. Once a static
     * display suit resolves its ArmorRendererData/layers, avoid repeating reflective lookups
     * every frame. Cleared when the render world/context changes.
     */
    private final Map<Item, RendererCache> rendererCache = new IdentityHashMap<>();

    PalladiumRenderBridge() {
        boolean present = ModList.get().isLoaded("palladium");
        Class<?> armorInterface = null;
        Class<?> rendererData = null;
        Method cached = null;
        Method layers = null;
        Method dataContext = null;
        Method layerRender = null;

        if (present) {
            try {
                ClassLoader loader = PalladiumRenderBridge.class.getClassLoader();
                armorInterface = Class.forName("net.threetag.palladium.item.ArmorWithRenderer", false, loader);
                rendererData = Class.forName(
                        "net.threetag.palladium.client.renderer.item.armor.ArmorRendererData", false, loader);
                Class<?> dataContextClass = Class.forName(
                        "net.threetag.palladium.util.context.DataContext", false, loader);
                Class<?> renderLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.IPackRenderLayer", false, loader);

                cached = armorInterface.getMethod("getCachedArmorRenderer");
                layers = rendererData.getMethod("getRenderLayers");
                dataContext = dataContextClass.getMethod(
                        "forArmorInSlot", net.minecraft.world.entity.LivingEntity.class, EquipmentSlot.class);
                layerRender = renderLayerClass.getMethod(
                        "render",
                        dataContextClass,
                        PoseStack.class,
                        MultiBufferSource.class,
                        EntityModel.class,
                        int.class,
                        float.class, float.class, float.class,
                        float.class, float.class, float.class
                );
            } catch (Throwable ignored) {
                present = false;
            }
        }

        this.installed = present;
        this.healthy = present;
        this.armorWithRendererClass = armorInterface;
        this.armorRendererDataClass = rendererData;
        this.getCachedArmorRenderer = cached;
        this.getRenderLayers = layers;
        this.forArmorInSlot = dataContext;
        this.renderLayer = layerRender;
    }

    boolean canUseFastPath(HeroStandBlockEntity stand) {
        if (!installed || !healthy) return false;

        boolean foundArmor = false;
        try {
            for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
                ItemStack stack = stand.getArmor(i);
                if (stack.isEmpty()) continue;
                foundArmor = true;

                Item item = stack.getItem();
                if (!armorWithRendererClass.isInstance(item)) return false;
                if (rendererFor(item) == null) return false;
            }
            return foundArmor;
        } catch (Throwable ignored) {
            disableFastPath();
            return false;
        }
    }

    /**
     * Renders only ArmorRendererData pack layers. Ability/player-global layers are intentionally
     * omitted because a HeroStand is a static display, not a powered living wearer.
     */
    void renderPackLayers(ArmorStand entity, EntityModel<?> parentModel, PoseStack poseStack,
                          MultiBufferSource buffers, int packedLight, float partialTick) {
        if (!healthy) return;

        try {
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                ItemStack stack = entity.getItemBySlot(slot);
                if (stack.isEmpty()) continue;

                RendererCache cache = rendererFor(stack.getItem());
                if (cache == null || cache.layers.isEmpty()) continue;

                Object context = forArmorInSlot.invoke(null, entity, slot);
                for (Object layer : cache.layers) {
                    renderLayer.invoke(
                            layer, context, poseStack, buffers, parentModel, packedLight,
                            0.0F, 0.0F, partialTick, 0.0F, 0.0F, 0.0F
                    );
                }
            }
        } catch (Throwable ignored) {
            disableFastPath();
        }
    }

    void resetSessionCache() {
        rendererCache.clear();
    }

    private RendererCache rendererFor(Item item) throws Exception {
        if (rendererCache.containsKey(item)) {
            return rendererCache.get(item);
        }

        Object renderer = getCachedArmorRenderer.invoke(item);
        if (renderer == null || !armorRendererDataClass.isInstance(renderer)) {
            rendererCache.put(item, null);
            return null;
        }

        Object rawLayers = getRenderLayers.invoke(renderer);
        List<?> layers = rawLayers instanceof List<?> list ? List.copyOf(list) : List.of();
        RendererCache next = new RendererCache(renderer, layers);
        rendererCache.put(item, next);
        return next;
    }

    private void disableFastPath() {
        healthy = false;
        rendererCache.clear();
    }

    private record RendererCache(Object renderer, List<?> layers) {}
}
