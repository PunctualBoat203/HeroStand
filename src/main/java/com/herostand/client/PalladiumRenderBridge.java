package com.herostand.client;

import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
    private boolean directArmorHealthy;

    private final Class<?> armorWithRendererClass;
    private final Class<?> armorRendererDataClass;
    private final Class<?> cancelGeckoArmorBufferClass;

    private final Method getCachedArmorRenderer;
    private final Method getRenderLayers;
    private final Method getArmorModel;
    private final Method getArmorTexture;
    private final Method getArmorTextureByKey;
    private final Method getArmorTranslucent;
    private final MethodHandle forArmorInSlot;
    private final MethodHandle dataContextWith;
    private final MethodHandle renderLayer;
    private final Object itemContextType;

    private final PalladiumStaticLayerBridge staticLayers = new PalladiumStaticLayerBridge();

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
     * seen in each slot so the hot pack-layer loop can avoid even the identity-map lookup.
     */
    private final Item[] lastSlotItems = new Item[ARMOR_SLOTS.length];
    private final RendererCache[] lastSlotRenderers = new RendererCache[ARMOR_SLOTS.length];

    /**
     * Direct Palladium armor cache. It intentionally lives for one render frame only: model/texture
     * conditions are still re-evaluated every frame, but not once per identical HeroStand.
     */
    private int directFrameBits = Integer.MIN_VALUE;
    private final Item[] directItems = new Item[ARMOR_SLOTS.length];
    private final int[] directDamage = new int[ARMOR_SLOTS.length];
    private final int[] directTagHash = new int[ARMOR_SLOTS.length];
    private final DirectArmorVisual[] directVisuals = new DirectArmorVisual[ARMOR_SLOTS.length];
    private final DirectArmorVisual[] directScratch = new DirectArmorVisual[ARMOR_SLOTS.length];

    PalladiumRenderBridge() {
        boolean present = ModList.get().isLoaded("palladium");
        Class<?> armorInterface = null;
        Class<?> rendererData = null;
        Class<?> geckoCancel = null;

        Method cached = null;
        Method layers = null;
        Method armorModel = null;
        Method armorTexture = null;
        Method armorTextureByKey = null;
        Method armorTranslucent = null;
        MethodHandle dataContext = null;
        MethodHandle contextWith = null;
        MethodHandle layerRender = null;
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
                Class<?> palladiumRenderTypes = Class.forName(
                        "net.threetag.palladium.client.renderer.PalladiumRenderTypes", false, loader);

                try {
                    geckoCancel = Class.forName(
                            "net.threetag.palladium.compat.geckolib.armor.CancelGeckoArmorBuffer",
                            false, loader);
                } catch (Throwable ignored) {
                    geckoCancel = null;
                }

                cached = armorInterface.getMethod("getCachedArmorRenderer");
                layers = rendererData.getMethod("getRenderLayers");
                armorModel = rendererData.getMethod(
                        "getModel",
                        net.minecraft.world.entity.LivingEntity.class,
                        dataContextClass
                );
                armorTexture = rendererData.getMethod("getTexture", dataContextClass);
                armorTextureByKey = rendererData.getMethod("getTexture", dataContextClass, String.class);
                armorTranslucent = palladiumRenderTypes.getMethod(
                        "getArmorTranslucent", ResourceLocation.class);

                MethodHandles.Lookup lookup = MethodHandles.lookup();

                dataContext = lookup.unreflect(dataContextClass.getMethod(
                                "forArmorInSlot",
                                net.minecraft.world.entity.LivingEntity.class,
                                EquipmentSlot.class))
                        .asType(MethodType.methodType(
                                Object.class,
                                net.minecraft.world.entity.LivingEntity.class,
                                EquipmentSlot.class));

                contextWith = lookup.unreflect(dataContextClass.getMethod(
                                "with", dataContextTypeClass, Object.class))
                        .asType(MethodType.methodType(
                                void.class,
                                Object.class, Object.class, Object.class));

                itemType = dataContextTypeClass.getField("ITEM").get(null);

                layerRender = lookup.unreflect(renderLayerClass.getMethod(
                                "render",
                                dataContextClass,
                                PoseStack.class,
                                MultiBufferSource.class,
                                EntityModel.class,
                                int.class,
                                float.class, float.class, float.class,
                                float.class, float.class, float.class))
                        .asType(MethodType.methodType(
                                void.class,
                                Object.class, Object.class,
                                PoseStack.class, MultiBufferSource.class, EntityModel.class,
                                int.class,
                                float.class, float.class, float.class,
                                float.class, float.class, float.class));
            } catch (Throwable ignored) {
                present = false;
            }
        }

        this.installed = present;
        this.healthy = present;
        this.directArmorHealthy = present;
        this.armorWithRendererClass = armorInterface;
        this.armorRendererDataClass = rendererData;
        this.cancelGeckoArmorBufferClass = geckoCancel;
        this.getCachedArmorRenderer = cached;
        this.getRenderLayers = layers;
        this.getArmorModel = armorModel;
        this.getArmorTexture = armorTexture;
        this.getArmorTextureByKey = armorTextureByKey;
        this.getArmorTranslucent = armorTranslucent;
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
     * Bypasses HumanoidArmorLayer only for standard Palladium custom armor. Palladium's mixin for
     * HumanoidArmorLayer ultimately resolves ArmorRendererData, copies the neutral parent pose,
     * selects slot visibility, obtains a Palladium translucent armor RenderType, and renders the
     * model. HeroStand can do that directly while reusing the already-existing DataContexts.
     *
     * Returns false without drawing anything when the armor needs an unsupported path (notably
     * Gecko armor), so the caller can use the original HumanoidArmorLayer unchanged.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    boolean renderArmorDirect(ArmorStand entity,
                              HumanoidModel<?> parentModel,
                              HumanoidModel<?> innerFallback,
                              HumanoidModel<?> outerFallback,
                              PoseStack poseStack,
                              MultiBufferSource buffers,
                              int packedLight,
                              float partialTick) {
        if (!directArmorHealthy) return false;

        try {
            ensureArmorContexts(entity);
            beginDirectFrame(partialTick);

            // Preflight every piece before emitting vertices. If any piece requires the old path,
            // return false so the caller can render the complete armor set through HumanoidArmorLayer.
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                EquipmentSlot slot = ARMOR_SLOTS[i];
                ItemStack stack = entity.getItemBySlot(slot);

                if (stack.isEmpty()) {
                    directScratch[i] = null;
                    continue;
                }

                if (!(stack.getItem() instanceof ArmorItem armorItem)
                        || armorItem.getEquipmentSlot() != slot) {
                    directScratch[i] = null;
                    continue;
                }

                Object context = armorContexts[i];
                dataContextWith.invokeExact(context, itemContextType, (Object) stack);

                RendererCache cache = rendererFor(stack.getItem());
                if (cache == null) return false;

                DirectArmorVisual visual = directVisualFor(
                        i, stack, entity, context, cache,
                        slot == EquipmentSlot.LEGS ? innerFallback : outerFallback,
                        parentModel
                );

                if (visual == null) return false;
                directScratch[i] = visual;
            }

            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                DirectArmorVisual visual = directScratch[i];
                if (visual == null) continue;

                EquipmentSlot slot = ARMOR_SLOTS[i];
                ItemStack stack = entity.getItemBySlot(slot);
                renderDirectPiece(visual, stack, slot, poseStack, buffers, packedLight);
            }

            return true;
        } catch (Throwable ignored) {
            // Keep the established renderer as the safety net if Palladium internals differ.
            directArmorHealthy = false;
            clearDirectCache();
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
                dataContextWith.invokeExact(context, itemContextType, (Object) stack);

                for (Object layer : cache.layers) {
                    boolean handled = staticLayers.renderIfSupported(
                            layer, context, entity, parentModel, stack, slot,
                            poseStack, buffers, packedLight, partialTick,
                            entity.level().getGameTime()
                    );

                    if (!handled) {
                        renderLayer.invokeExact(
                                layer, context, poseStack, buffers, parentModel, packedLight,
                                0.0F, 0.0F, partialTick, 0.0F, 0.0F, 0.0F
                        );
                    }
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
        directArmorHealthy = installed;
        clearDirectCache();
        staticLayers.resetSessionCache();

        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            armorContexts[i] = null;
            lastSlotItems[i] = null;
            lastSlotRenderers[i] = null;
        }
    }

    private void beginDirectFrame(float partialTick) {
        int bits = Float.floatToIntBits(partialTick);
        if (bits == directFrameBits) return;

        directFrameBits = bits;
        clearDirectVisualsOnly();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DirectArmorVisual directVisualFor(int slotIndex,
                                               ItemStack stack,
                                               ArmorStand entity,
                                               Object context,
                                               RendererCache cache,
                                               HumanoidModel<?> fallbackModel,
                                               HumanoidModel<?> parentModel) throws Exception {
        Item item = stack.getItem();
        int damage = stack.getDamageValue();
        int tagHash = stack.hasTag() ? stack.getTag().hashCode() : 0;

        if (directItems[slotIndex] == item
                && directDamage[slotIndex] == damage
                && directTagHash[slotIndex] == tagHash
                && directVisuals[slotIndex] != null) {
            return directVisuals[slotIndex];
        }

        Object rawModel = getArmorModel.invoke(cache.renderer, entity, context);
        HumanoidModel model = rawModel instanceof HumanoidModel humanoid
                ? humanoid
                : (HumanoidModel) fallbackModel;

        if (cancelGeckoArmorBufferClass != null && cancelGeckoArmorBufferClass.isInstance(model)) {
            return null;
        }

        ResourceLocation texture = (ResourceLocation) getArmorTexture.invoke(cache.renderer, context);
        RenderType renderType = (RenderType) getArmorTranslucent.invoke(null, texture);

        RenderType overlayRenderType = null;
        if (item instanceof DyeableArmorItem) {
            ResourceLocation overlay =
                    (ResourceLocation) getArmorTextureByKey.invoke(cache.renderer, context, "overlay");
            overlayRenderType = (RenderType) getArmorTranslucent.invoke(null, overlay);
        }

        // Parent pose is static/neutral for HeroStand. Do this once per item/slot per frame instead
        // of once per stand through HumanoidArmorLayer.
        ((HumanoidModel) parentModel).copyPropertiesTo(model);

        DirectArmorVisual visual = new DirectArmorVisual(model, renderType, overlayRenderType);
        directItems[slotIndex] = item;
        directDamage[slotIndex] = damage;
        directTagHash[slotIndex] = tagHash;
        directVisuals[slotIndex] = visual;
        return visual;
    }

    @SuppressWarnings("rawtypes")
    private static void renderDirectPiece(DirectArmorVisual visual,
                                          ItemStack stack,
                                          EquipmentSlot slot,
                                          PoseStack poseStack,
                                          MultiBufferSource buffers,
                                          int packedLight) {
        HumanoidModel model = visual.model;
        setPartVisibility(model, slot);

        boolean foil = stack.hasFoil();

        if (stack.getItem() instanceof DyeableArmorItem dyeable) {
            int color = dyeable.getColor(stack);
            float red = (float) (color >> 16 & 255) / 255.0F;
            float green = (float) (color >> 8 & 255) / 255.0F;
            float blue = (float) (color & 255) / 255.0F;

            renderModel(model, visual.renderType, stack, poseStack, buffers, packedLight,
                    foil, red, green, blue);

            if (visual.overlayRenderType != null) {
                renderModel(model, visual.overlayRenderType, stack, poseStack, buffers, packedLight,
                        foil, 1.0F, 1.0F, 1.0F);
            }
        } else {
            renderModel(model, visual.renderType, stack, poseStack, buffers, packedLight,
                    foil, 1.0F, 1.0F, 1.0F);
        }
    }

    @SuppressWarnings("rawtypes")
    private static void renderModel(HumanoidModel model,
                                    RenderType renderType,
                                    ItemStack stack,
                                    PoseStack poseStack,
                                    MultiBufferSource buffers,
                                    int packedLight,
                                    boolean foil,
                                    float red, float green, float blue) {
        VertexConsumer consumer =
                ItemRenderer.getArmorFoilBuffer(buffers, renderType, false, foil);
        model.renderToBuffer(
                poseStack,
                consumer,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                red, green, blue, 1.0F
        );
    }

    @SuppressWarnings("rawtypes")
    private static void setPartVisibility(HumanoidModel model, EquipmentSlot slot) {
        model.setAllVisible(false);

        switch (slot) {
            case HEAD -> {
                model.head.visible = true;
                model.hat.visible = true;
            }
            case CHEST -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case LEGS -> {
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            case FEET -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            default -> {
            }
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
            armorContexts[i] = (Object) forArmorInSlot.invokeExact(
                    (net.minecraft.world.entity.LivingEntity) entity,
                    ARMOR_SLOTS[i]
            );
            lastSlotItems[i] = null;
            lastSlotRenderers[i] = null;
        }

        clearDirectCache();
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

    private void clearDirectCache() {
        directFrameBits = Integer.MIN_VALUE;
        clearDirectVisualsOnly();
    }

    private void clearDirectVisualsOnly() {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            directItems[i] = null;
            directDamage[i] = 0;
            directTagHash[i] = 0;
            directVisuals[i] = null;
            directScratch[i] = null;
        }
    }

    private void disableFastPath() {
        healthy = false;
        directArmorHealthy = false;
        rendererCache.clear();
        fastPathEligibility.clear();
        clearDirectCache();
        staticLayers.resetSessionCache();
    }

    private record RendererCache(Object renderer, List<?> layers) {}

    @SuppressWarnings("rawtypes")
    private record DirectArmorVisual(
            HumanoidModel model,
            RenderType renderType,
            RenderType overlayRenderType
    ) {}
}
