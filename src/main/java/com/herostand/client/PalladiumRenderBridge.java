package com.herostand.client;

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
 * Optional Palladium integration. This class only touches Palladium through reflection so
 * HeroStand remains fully usable when Palladium is not installed.
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

    /**
     * The fast path is intentionally conservative: every equipped item must be a Palladium
     * ArmorWithRenderer item with a loaded ArmorRendererData object. Mixed/unknown armor
     * falls back to Minecraft's normal EntityRenderDispatcher path.
     */
    boolean canUseFastPath(ArmorStand entity) {
        if (!installed || !healthy) return false;

        boolean foundArmor = false;
        try {
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                ItemStack stack = entity.getItemBySlot(slot);
                if (stack.isEmpty()) continue;
                foundArmor = true;

                Item item = stack.getItem();
                if (!armorWithRendererClass.isInstance(item)) return false;
                if (rendererFor(item) == null) return false;
            }
            return foundArmor;
        } catch (Throwable ignored) {
            healthy = false;
            rendererCache.clear();
            return false;
        }
    }

    /**
     * Renders only the pack layers attached to each Palladium ArmorRendererData.
     * Ability-provided/player-only global render layers are deliberately skipped for a static
     * display stand.
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
            healthy = false;
            rendererCache.clear();
        }
    }

    private RendererCache rendererFor(Item item) throws Exception {
        Object renderer = getCachedArmorRenderer.invoke(item);
        if (renderer == null || !armorRendererDataClass.isInstance(renderer)) return null;

        RendererCache cached = rendererCache.get(item);
        if (cached != null && cached.renderer == renderer) return cached;

        Object rawLayers = getRenderLayers.invoke(renderer);
        List<?> layers = rawLayers instanceof List<?> list ? List.copyOf(list) : List.of();
        RendererCache next = new RendererCache(renderer, layers);
        rendererCache.put(item, next);
        return next;
    }

    private record RendererCache(Object renderer, List<?> layers) {}
}
