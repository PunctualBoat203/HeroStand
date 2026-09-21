package com.herostand.client;

import com.herostand.world.HeroStandBlockEntity;
import net.minecraft.core.Rotations;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Palladium adapter used by the 0.2 renderer.
 *
 * Visual correctness is delegated to Palladium itself: HeroStand renders/captures a real
 * client-only Palladium SuitStand instead of recreating Palladium's transforms/models manually.
 * Reflection keeps Palladium optional.
 */
final class PalladiumNativeBridge {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final Rotations ZERO = new Rotations(0.0F, 0.0F, 0.0F);

    private final boolean installed;
    private boolean healthy;

    private final Constructor<?> suitStandConstructor;
    private final Class<?> armorWithRendererClass;
    private final Class<?> armorRendererDataClass;
    private final Class<?> addonItemClass;
    private final Class<?> packLayerClass;
    private final Class<?> skinOverlayLayerClass;
    private final Class<?> compoundLayerClass;
    private final Class<?> thrusterLayerClass;
    private final Class<?> lightningLayerClass;
    private final Class<?> extraAnimatedModelClass;

    private final Method getCachedArmorRenderer;
    private final Method getRenderLayers;
    private final Method getArmorModel;
    private final Method forArmorInSlot;
    private final Method getAddonLayerContainer;
    private final Method containerGet;
    private final Method layerManagerGetInstance;
    private final Method layerManagerGetLayer;
    private final Method compoundLayers;

    private final Method skinTypedGet;
    private final Method packModelLookupGet;
    private final Method packModelCacheGet;
    private final Method modelCacheGetModel;

    private final Map<Item, RendererInfo> rendererCache = new IdentityHashMap<>();
    private final Map<Item, Boolean> snapshotSafetyCache = new IdentityHashMap<>();

    private ArmorStand context;
    private Level contextLevel;

    PalladiumNativeBridge() {
        boolean present = ModList.get().isLoaded("palladium");

        Constructor<?> suitCtor = null;
        Class<?> armorInterface = null;
        Class<?> rendererData = null;
        Class<?> addonItem = null;
        Class<?> packLayer = null;
        Class<?> skinLayer = null;
        Class<?> compoundLayer = null;
        Class<?> thrusterLayer = null;
        Class<?> lightningLayer = null;
        Class<?> extraAnimated = null;

        Method cachedRenderer = null;
        Method renderLayers = null;
        Method armorModel = null;
        Method armorContext = null;
        Method addonContainer = null;
        Method containerLayers = null;
        Method managerInstance = null;
        Method managerLayer = null;
        Method childLayers = null;

        Method skinGet = null;
        Method packLookupGet = null;
        Method packCacheGet = null;
        Method modelGet = null;

        if (present) {
            try {
                ClassLoader loader = PalladiumNativeBridge.class.getClassLoader();

                Class<?> suitStandClass = Class.forName(
                        "net.threetag.palladium.entity.SuitStand", false, loader);
                armorInterface = Class.forName(
                        "net.threetag.palladium.item.ArmorWithRenderer", false, loader);
                rendererData = Class.forName(
                        "net.threetag.palladium.client.renderer.item.armor.ArmorRendererData",
                        false, loader);
                addonItem = Class.forName(
                        "net.threetag.palladium.item.IAddonItem", false, loader);

                packLayer = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayer",
                        false, loader);
                skinLayer = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.SkinOverlayPackRenderLayer",
                        false, loader);
                compoundLayer = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.CompoundPackRenderLayer",
                        false, loader);
                thrusterLayer = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.ThrusterPackRenderLayer",
                        false, loader);
                lightningLayer = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.LightningSparksRenderLayer",
                        false, loader);
                extraAnimated = Class.forName(
                        "net.threetag.palladium.client.model.ExtraAnimatedModel",
                        false, loader);

                Class<?> dataContextClass = Class.forName(
                        "net.threetag.palladium.util.context.DataContext", false, loader);
                Class<?> renderLayerContainerClass = Class.forName(
                        "net.threetag.palladium.item.IAddonItem$RenderLayerContainer",
                        false, loader);
                Class<?> layerManagerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayerManager",
                        false, loader);
                Class<?> skinTypedValueClass = Class.forName(
                        "net.threetag.palladium.util.SkinTypedValue", false, loader);
                Class<?> modelCacheClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayer$ModelCache",
                        false, loader);

                suitCtor = suitStandClass.getConstructor(
                        Level.class, double.class, double.class, double.class);

                cachedRenderer = armorInterface.getMethod("getCachedArmorRenderer");
                renderLayers = rendererData.getMethod("getRenderLayers");
                armorModel = rendererData.getMethod(
                        "getModel", LivingEntity.class, dataContextClass);
                armorContext = dataContextClass.getMethod(
                        "forArmorInSlot", LivingEntity.class, EquipmentSlot.class);

                addonContainer = addonItem.getMethod("getRenderLayerContainer");
                containerLayers = renderLayerContainerClass.getMethod("get", String.class);
                managerInstance = layerManagerClass.getMethod("getInstance");
                managerLayer = layerManagerClass.getMethod("getLayer", ResourceLocation.class);
                childLayers = compoundLayer.getMethod("layers");

                skinGet = skinTypedValueClass.getMethod(
                        "get", net.minecraft.world.entity.Entity.class);
                packLookupGet = packLayer.getMethod("getModelLookup");
                packCacheGet = packLayer.getMethod("getModel");
                modelGet = modelCacheClass.getMethod(
                        "getModel", dataContextClass,
                        Class.forName(
                                "net.threetag.palladium.client.renderer.renderlayer.ModelTypes$Model",
                                false, loader));
            } catch (Throwable ignored) {
                present = false;
            }
        }

        this.installed = present;
        this.healthy = present;

        this.suitStandConstructor = suitCtor;
        this.armorWithRendererClass = armorInterface;
        this.armorRendererDataClass = rendererData;
        this.addonItemClass = addonItem;
        this.packLayerClass = packLayer;
        this.skinOverlayLayerClass = skinLayer;
        this.compoundLayerClass = compoundLayer;
        this.thrusterLayerClass = thrusterLayer;
        this.lightningLayerClass = lightningLayer;
        this.extraAnimatedModelClass = extraAnimated;

        this.getCachedArmorRenderer = cachedRenderer;
        this.getRenderLayers = renderLayers;
        this.getArmorModel = armorModel;
        this.forArmorInSlot = armorContext;
        this.getAddonLayerContainer = addonContainer;
        this.containerGet = containerLayers;
        this.layerManagerGetInstance = managerInstance;
        this.layerManagerGetLayer = managerLayer;
        this.compoundLayers = childLayers;

        this.skinTypedGet = skinGet;
        this.packModelLookupGet = packLookupGet;
        this.packModelCacheGet = packCacheGet;
        this.modelCacheGetModel = modelGet;
    }

    boolean isInstalled() {
        return installed && healthy;
    }

    boolean isPalladiumSuit(HeroStandBlockEntity stand) {
        if (!isInstalled()) return false;

        boolean found = false;
        try {
            for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
                ItemStack stack = stand.getArmor(i);
                if (stack.isEmpty()) continue;
                found = true;

                if (!armorWithRendererClass.isInstance(stack.getItem())) {
                    return false;
                }
                if (rendererFor(stack.getItem()) == null) {
                    return false;
                }
            }
            return found;
        } catch (Throwable failure) {
            disable();
            return false;
        }
    }

    ArmorStand prepareContext(HeroStandBlockEntity stand, Level level) {
        if (!isInstalled()) return null;

        try {
            if (context == null || contextLevel != level) {
                Object created = suitStandConstructor.newInstance(
                        level, 0.0D, 0.0D, 0.0D);
                if (!(created instanceof ArmorStand armorStand)) {
                    disable();
                    return null;
                }

                context = armorStand;
                contextLevel = level;

                // Keep Palladium's normal scale/Y-offset semantics, but never show the physical
                // mannequin itself. The HeroStand block supplies the pedestal.
                context.setInvisible(true);
                context.setNoBasePlate(false);
                context.setShowArms(true);

                context.setHeadPose(ZERO);
                context.setBodyPose(ZERO);
                context.setLeftArmPose(ZERO);
                context.setRightArmPose(ZERO);
                context.setLeftLegPose(ZERO);
                context.setRightLegPose(ZERO);
            }

            copyEquipment(stand, context);
            pinRotation(context);
            return context;
        } catch (Throwable failure) {
            disable();
            return null;
        }
    }

    /**
     * Conservative snapshot policy. Known static Palladium layer types can be captured. Thrusters,
     * lightning, unknown/custom layer classes, and models with ExtraAnimatedModel stay live.
     */
    boolean isSnapshotSafe(ArmorStand suitStand) {
        if (!isInstalled() || suitStand == null) return false;

        try {
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                ItemStack stack = suitStand.getItemBySlot(slot);
                if (stack.isEmpty()) continue;

                Item item = stack.getItem();
                Boolean cached = snapshotSafetyCache.get(item);
                if (cached != null) {
                    if (!cached) return false;
                    continue;
                }

                boolean safe = itemSnapshotSafe(item, suitStand, slot);
                snapshotSafetyCache.put(item, safe);
                if (!safe) return false;
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    void reset() {
        rendererCache.clear();
        snapshotSafetyCache.clear();
        context = null;
        contextLevel = null;
        healthy = installed;
    }

    private boolean itemSnapshotSafe(Item item,
                                     ArmorStand suitStand,
                                     EquipmentSlot slot) throws Exception {
        RendererInfo info = rendererFor(item);
        if (info == null) return false;

        Object context = forArmorInSlot.invoke(null, suitStand, slot);

        Object armorModel = getArmorModel.invoke(info.renderer, suitStand, context);
        if (armorModel != null && extraAnimatedModelClass.isInstance(armorModel)) {
            return false;
        }

        for (Object layer : info.layers) {
            if (!layerSnapshotSafe(layer, suitStand, context)) return false;
        }

        if (addonItemClass.isInstance(item)) {
            Object container = getAddonLayerContainer.invoke(item);
            if (container != null) {
                Object rawIds = containerGet.invoke(container, slot.getName());
                if (rawIds instanceof List<?> ids && !ids.isEmpty()) {
                    Object manager = layerManagerGetInstance.invoke(null);
                    for (Object rawId : ids) {
                        if (!(rawId instanceof ResourceLocation id)) return false;
                        Object layer = layerManagerGetLayer.invoke(manager, id);
                        if (layer == null || !layerSnapshotSafe(layer, suitStand, context)) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    private boolean layerSnapshotSafe(Object layer,
                                      ArmorStand suitStand,
                                      Object dataContext) throws Exception {
        if (layer == null) return true;

        if (thrusterLayerClass.isInstance(layer)
                || lightningLayerClass.isInstance(layer)) {
            return false;
        }

        if (skinOverlayLayerClass.isInstance(layer)) {
            return true;
        }

        if (compoundLayerClass.isInstance(layer)) {
            Object rawChildren = compoundLayers.invoke(layer);
            if (!(rawChildren instanceof List<?> children)) return false;
            for (Object child : children) {
                if (!layerSnapshotSafe(child, suitStand, dataContext)) return false;
            }
            return true;
        }

        if (packLayerClass.isInstance(layer)) {
            try {
                Object modelLookup = packModelLookupGet.invoke(layer);
                Object modelType = skinTypedGet.invoke(modelLookup, suitStand);
                Object modelCacheValue = packModelCacheGet.invoke(layer);
                Object modelCache = skinTypedGet.invoke(modelCacheValue, suitStand);
                Object model = modelCacheGetModel.invoke(modelCache, dataContext, modelType);
                return model == null || !extraAnimatedModelClass.isInstance(model);
            } catch (Throwable ignored) {
                return false;
            }
        }

        // Add-on/custom layer classes can perform direct GL work or depend on game time.
        return false;
    }

    private RendererInfo rendererFor(Item item) throws Exception {
        if (rendererCache.containsKey(item)) {
            return rendererCache.get(item);
        }

        Object renderer = getCachedArmorRenderer.invoke(item);
        if (renderer == null || !armorRendererDataClass.isInstance(renderer)) {
            rendererCache.put(item, null);
            return null;
        }

        Object rawLayers = getRenderLayers.invoke(renderer);
        List<?> layers = rawLayers instanceof List<?> list
                ? List.copyOf(list)
                : List.of();

        RendererInfo info = new RendererInfo(renderer, layers);
        rendererCache.put(item, info);
        return info;
    }

    private static void copyEquipment(HeroStandBlockEntity stand, ArmorStand context) {
        context.setItemSlot(
                EquipmentSlot.HEAD,
                stand.getArmor(HeroStandBlockEntity.HEAD));
        context.setItemSlot(
                EquipmentSlot.CHEST,
                stand.getArmor(HeroStandBlockEntity.CHEST));
        context.setItemSlot(
                EquipmentSlot.LEGS,
                stand.getArmor(HeroStandBlockEntity.LEGS));
        context.setItemSlot(
                EquipmentSlot.FEET,
                stand.getArmor(HeroStandBlockEntity.FEET));
    }

    private static void pinRotation(ArmorStand context) {
        context.setYRot(0.0F);
        context.yRotO = 0.0F;
        context.setYHeadRot(0.0F);
        context.yHeadRotO = 0.0F;
        context.yBodyRot = 0.0F;
        context.yBodyRotO = 0.0F;
        context.setXRot(0.0F);
        context.xRotO = 0.0F;
    }

    private void disable() {
        healthy = false;
        rendererCache.clear();
        snapshotSafetyCache.clear();
        context = null;
        contextLevel = null;
    }

    private record RendererInfo(Object renderer, List<?> layers) {}
}
