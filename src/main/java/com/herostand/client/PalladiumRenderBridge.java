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
    private final Method dataContextWith;
    private final Method renderLayer;
    private final Object itemContextType;

    /**
     * Identity caches are intentional: Minecraft Items are registry singletons. Once a static
     * display suit resolves its ArmorRendererData/layers or fast-path eligibility, avoid repeating
     * reflective lookups every frame.
     */
    private final Map<Item, RendererCache> rendererCache = new IdentityHashMap<>();
    private final Map<Item, Boolean> fastPathEligibility = new IdentityHashMap<>();

    /**
     * HeroStand reuses one client-only ArmorStand for the Palladium fast path. Palladium's
     * DataContext.forArmorInSlot(...) allocates a DataContext + HashMap each call, so reuse one
     * context per slot for that reusable entity and only replace the ITEM value each draw.
     */
    private ArmorStand cachedContextEntity;
    private final Object[] armorContexts = new Object[ARMOR_SLOTS.length];

    /**
     * The stress case is many neighboring stands wearing the same suit. Remember the last item
     * seen in each slot so the hot render loop can avoid even the identity-map lookup.
     */
    private final Item[] lastSlotItems = new Item[ARMOR_SLOTS.length];
    private final RendererCache[] lastSlotRenderers = new RendererCache[ARMOR_SLOTS.length];

    PalladiumRenderBridge() {
        boolean present = ModList.get().isLoaded("palladium");
        Class<?> armorInterface = null;
        Class<?> rendererData = null;
        Method cached = null;
        Method layers = null;
        Method dataContext = null;
        Method contextWith = null;
        Method layerRender = null;
        Object itemType = null;

        if (present) {
            try {
                ClassLoader loader = PalladiumRenderBridge.class.getClassLoader();
                armorInterface = Class.forName("net.threetag.palladium.item.ArmorWithRenderer", false, loader);
                rendererData = Class.forName(
                        "net.threetag.palladium.client.renderer.item.armor.ArmorRendererData", false, loader);
                Class<?> dataContextClass = Class.forName(
                        "net.threetag.palladium.util.context.DataContext", false, loader);
                Class<?> dataContextTypeClass = Class.forName(
                        "net.threetag.palladium.util.context.DataContextType", false, loader);
                Class<?> renderLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.IPackRenderLayer", false, loader);

                cached = armorInterface.getMethod("getCachedArmorRenderer");
                layers = rendererData.getMethod("getRenderLayers");
                dataContext = dataContextClass.getMethod(
                        "forArmorInSlot", net.minecraft.world.entity.LivingEntity.class, EquipmentSlot.class);
                contextWith = dataContextClass.getMethod("with", dataContextTypeClass, Object.class);
                itemType = dataContextTypeClass.getField("ITEM").get(null);
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
        this.dataContextWith = contextWith;
        this.renderLayer = layerRender;
        this.itemContextType = itemType;
    }

    boolean canUseFastPath(HeroStandBlockEntity stand) {
        if (!installed || !healthy) return false;

        boolean foundArmor = false;
        try {
            for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
                ItemStack stack = stand.getArmor(i);
                if (stack.isEmpty()) continue;
                foundArmor = true;

                if (!supportsFastPath(stack.getItem())) return false;
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
            ensureArmorContexts(entity);

            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                EquipmentSlot slot = ARMOR_SLOTS[i];
                ItemStack stack = entity.getItemBySlot(slot);
                if (stack.isEmpty()) continue;

                Item item = stack.getItem();
                RendererCache cache;
                if (lastSlotItems[i] == item) {
                    cache = lastSlotRenderers[i];
                } else {
                    cache = rendererFor(item);
                    lastSlotItems[i] = item;
                    lastSlotRenderers[i] = cache;
                }

                if (cache == null || cache.layers.isEmpty()) continue;

                Object context = armorContexts[i];

                // DataContext is mutable. ENTITY/LEVEL/SLOT stay constant because this fast-path
                // ArmorStand is reused; only ITEM needs to track the stand currently being drawn.
                dataContextWith.invoke(context, itemContextType, stack);

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
        fastPathEligibility.clear();
        cachedContextEntity = null;

        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            armorContexts[i] = null;
            lastSlotItems[i] = null;
            lastSlotRenderers[i] = null;
        }
    }

    private boolean supportsFastPath(Item item) throws Exception {
        Boolean cached = fastPathEligibility.get(item);
        if (cached != null) return cached;

        boolean supported = armorWithRendererClass.isInstance(item) && rendererFor(item) != null;
        fastPathEligibility.put(item, supported);
        return supported;
    }

    private void ensureArmorContexts(ArmorStand entity) throws Exception {
        if (cachedContextEntity == entity) return;

        cachedContextEntity = entity;
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            armorContexts[i] = forArmorInSlot.invoke(null, entity, ARMOR_SLOTS[i]);
            lastSlotItems[i] = null;
            lastSlotRenderers[i] = null;
        }
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
        resetSessionCache();
    }

    private record RendererCache(Object renderer, List<?> layers) {}
}
