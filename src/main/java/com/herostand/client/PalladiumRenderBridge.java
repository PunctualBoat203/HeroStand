package com.herostand.client;

import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
    private final Method getArmorModels;
    private final Method getArmorTexture;
    private final Method getArmorTextureByKey;
    private final Method getArmorTranslucent;
    private final MethodHandle forArmorInSlot;
    private final MethodHandle dataContextWith;
    private final MethodHandle renderLayer;
    private final Object itemContextType;

    // Conservative compatibility inspection. Custom model layers/render layers are routed to
    // Palladium's own SuitStandRenderer instead of HeroStand's manual paths.
    private final Field armorModelMapField;
    private final Class<?> packRenderLayerClass;
    private final Class<?> compoundPackRenderLayerClass;
    private final Class<?> skinOverlayPackRenderLayerClass;
    private final Class<?> thrusterPackRenderLayerClass;
    private final Class<?> lightningPackRenderLayerClass;
    private final Field packModelLookupField;
    private final Method compoundLayersMethod;
    private final Method skinTypedGet;
    private final Object humanoidModelType;

    private final PalladiumStaticLayerBridge staticLayers = new PalladiumStaticLayerBridge();

    /**
     * Identity caches are intentional: Minecraft Items are registry singletons. Once a static
     * display suit resolves its ArmorRendererData/layers or fast-path eligibility, avoid repeating
     * reflective lookups every frame.
     */
    private final Map<Item, RendererCache> rendererCache = new IdentityHashMap<>();
    private final Map<Item, Boolean> fastPathEligibility = new IdentityHashMap<>();
    private final Map<Item, Boolean> nativeRendererRequired = new IdentityHashMap<>();

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
     * Direct Palladium armor visual state is static for a display stand. Cache model/texture
     * resolution across frames (with a short refresh window for NBT/world-driven variants) so a
     * wall of different suits does not rebuild every armor piece every frame.
     */
    private static final long DIRECT_VISUAL_CACHE_TICKS = 40L;
    private static final long DIRECT_VISUAL_CACHE_SPREAD_TICKS = 20L;
    private static final int MAX_DIRECT_VISUAL_VARIANTS = 1024;

    private final Map<Item, List<DirectArmorVariant>> directArmorCache = new IdentityHashMap<>();
    private final DirectArmorVisual[] directScratch = new DirectArmorVisual[ARMOR_SLOTS.length];
    private int directArmorVariantCount;

    PalladiumRenderBridge() {
        boolean present = ModList.get().isLoaded("palladium");
        Class<?> armorInterface = null;
        Class<?> rendererData = null;
        Class<?> geckoCancel = null;

        Method cached = null;
        Method layers = null;
        Method armorModel = null;
        Method armorModels = null;
        Method armorTexture = null;
        Method armorTextureByKey = null;
        Method armorTranslucent = null;
        MethodHandle dataContext = null;
        MethodHandle contextWith = null;
        MethodHandle layerRender = null;
        Object itemType = null;

        Field modelMapField = null;
        Class<?> packLayerClass = null;
        Class<?> compoundLayerClass = null;
        Class<?> skinOverlayLayerClass = null;
        Class<?> thrusterLayerClass = null;
        Class<?> lightningLayerClass = null;
        Field packModelLookup = null;
        Method compoundLayers = null;
        Method skinGet = null;
        Object humanoidType = null;

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
                Class<?> armorModelDataClass = Class.forName(
                        "net.threetag.palladium.client.renderer.item.armor.ArmorModelData", false, loader);
                Class<?> skinTypedValueClass = Class.forName(
                        "net.threetag.palladium.util.SkinTypedValue", false, loader);
                Class<?> modelTypesClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.ModelTypes", false, loader);

                packLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayer", false, loader);
                compoundLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.CompoundPackRenderLayer", false, loader);
                skinOverlayLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.SkinOverlayPackRenderLayer", false, loader);
                thrusterLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.ThrusterPackRenderLayer", false, loader);
                lightningLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.LightningSparksRenderLayer", false, loader);

                modelMapField = armorModelDataClass.getDeclaredField("modelByKey");
                modelMapField.setAccessible(true);

                packModelLookup = packLayerClass.getDeclaredField("modelLookup");
                packModelLookup.setAccessible(true);

                compoundLayers = compoundLayerClass.getMethod("layers");
                skinGet = skinTypedValueClass.getMethod("get", net.minecraft.world.entity.Entity.class);
                humanoidType = modelTypesClass.getField("HUMANOID").get(null);

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
                armorModels = rendererData.getMethod("getModels");
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
        this.getArmorModels = armorModels;
        this.getArmorTexture = armorTexture;
        this.getArmorTextureByKey = armorTextureByKey;
        this.getArmorTranslucent = armorTranslucent;
        this.forArmorInSlot = dataContext;
        this.dataContextWith = contextWith;
        this.renderLayer = layerRender;
        this.itemContextType = itemType;

        this.armorModelMapField = modelMapField;
        this.packRenderLayerClass = packLayerClass;
        this.compoundPackRenderLayerClass = compoundLayerClass;
        this.skinOverlayPackRenderLayerClass = skinOverlayLayerClass;
        this.thrusterPackRenderLayerClass = thrusterLayerClass;
        this.lightningPackRenderLayerClass = lightningLayerClass;
        this.packModelLookupField = packModelLookup;
        this.compoundLayersMethod = compoundLayers;
        this.skinTypedGet = skinGet;
        this.humanoidModelType = humanoidType;
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
     * Manual HeroStand rendering is intentionally conservative. Palladium add-on packs can author
     * custom armor model layers and arbitrary pack-layer models. Those can be perfectly valid yet
     * look wrong when rendered through our simplified parent/model assumptions (Mark One is the
     * current regression). Detect those cases before drawing and route them to Palladium's real
     * SuitStandRenderer.
     */
    boolean requiresNativeRenderer(ArmorStand entity) {
        if (!installed || !healthy) return false;

        try {
            ensureArmorContexts(entity);

            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                EquipmentSlot slot = ARMOR_SLOTS[i];
                ItemStack stack = entity.getItemBySlot(slot);
                if (stack.isEmpty()) continue;

                Item item = stack.getItem();
                Boolean cached = nativeRendererRequired.get(item);
                if (cached != null) {
                    if (cached) return true;
                    continue;
                }

                RendererCache renderer = rendererFor(item);
                boolean requiresNative = renderer != null
                        && rendererRequiresNative(renderer, entity);

                nativeRendererRequired.put(item, requiresNative);
                if (requiresNative) return true;
            }

            return false;
        } catch (Throwable ignored) {
            // Unknown Palladium internals should fail toward correctness, not the aggressive path.
            return true;
        }
    }

    private boolean rendererRequiresNative(RendererCache cache, ArmorStand entity) throws Exception {
        /*
         * 0.1.19 correction:
         *
         * A custom armor ModelLayerLocation is NOT inherently unsafe. Palladium's own
         * HumanoidArmorLayerMixin resolves those custom HumanoidModels, copies parent properties,
         * applies slot visibility and renders them exactly like HeroStand's direct path does.
         *
         * The old native-only rule accidentally routed most superhero suits back through the full
         * SuitStand renderer. Mark One's half-size body / separated head was instead explained by
         * HeroStand leaving EntityModel.young=true on its manual parent model. That is now pinned
         * false in HeroStandRenderer.
         *
         * Keep native routing only for render-layer classes whose semantics we genuinely cannot
         * reproduce safely.
         */
        for (Object layer : cache.layers) {
            if (layerRequiresNative(layer, entity)) return true;
        }

        return false;
    }

    private boolean layerRequiresNative(Object layer, ArmorStand entity) throws Exception {
        if (layer == null) return false;

        if (packRenderLayerClass != null && packRenderLayerClass.isInstance(layer)) {
            /*
             * Important 0.1.18 correction: PackRenderLayer itself is not a reason to force the
             * expensive native SuitStandRenderer. HeroStand invokes Palladium's own layer.render
             * method with a real SuitStand DataContext, so custom model-layer selection remains
             * Palladium-owned. Mark One's regression came from its custom BASE armor model layer,
             * which is already caught earlier by rendererRequiresNative(...).
             */
            return false;
        }

        if (compoundPackRenderLayerClass != null
                && compoundPackRenderLayerClass.isInstance(layer)) {
            Object rawChildren = compoundLayersMethod.invoke(layer);
            if (rawChildren instanceof List<?> children) {
                for (Object child : children) {
                    if (layerRequiresNative(child, entity)) return true;
                }
            }
            return false;
        }

        // These built-in layers are explicitly written around humanoid/SuitStand state and are
        // safe in the balanced path.
        if (skinOverlayPackRenderLayerClass != null
                && skinOverlayPackRenderLayerClass.isInstance(layer)) {
            return false;
        }
        if (thrusterPackRenderLayerClass != null
                && thrusterPackRenderLayerClass.isInstance(layer)) {
            return false;
        }
        if (lightningPackRenderLayerClass != null
                && lightningPackRenderLayerClass.isInstance(layer)) {
            return false;
        }

        // Unknown/add-on/custom layer types get Palladium's native renderer.
        return true;
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
                        stack, slot, entity, context, cache,
                        slot == EquipmentSlot.LEGS ? innerFallback : outerFallback
                );

                if (visual == null) return false;
                directScratch[i] = visual;
            }

            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                DirectArmorVisual visual = directScratch[i];
                if (visual == null) continue;

                EquipmentSlot slot = ARMOR_SLOTS[i];
                ItemStack stack = entity.getItemBySlot(slot);
                renderDirectPiece(
                        visual, stack, slot, parentModel,
                        poseStack, buffers, packedLight
                );
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
    boolean renderPackLayers(ArmorStand entity, EntityModel<?> parentModel, PoseStack poseStack,
                             MultiBufferSource buffers, int packedLight, float partialTick,
                             boolean accelerated) {
        if (!healthy) return false;

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
                    boolean handled = accelerated && staticLayers.renderIfSupported(
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

            return true;
        } catch (Throwable ignored) {
            disableFastPath();
            return false;
        }
    }

    void resetSessionCache() {
        rendererCache.clear();
        fastPathEligibility.clear();
        nativeRendererRequired.clear();
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DirectArmorVisual directVisualFor(ItemStack stack,
                                               EquipmentSlot slot,
                                               ArmorStand entity,
                                               Object context,
                                               RendererCache cache,
                                               HumanoidModel<?> fallbackModel) throws Exception {
        Item item = stack.getItem();
        long gameTime = entity.level().getGameTime();

        List<DirectArmorVariant> variants =
                directArmorCache.computeIfAbsent(item, ignored -> new ArrayList<>(2));

        for (DirectArmorVariant variant : variants) {
            if (variant.slot == slot && ItemStack.isSameItemSameTags(variant.stack, stack)) {
                if (gameTime >= variant.expiresAt) {
                    variant.visual = resolveDirectArmorVisual(
                            stack, entity, context, cache, fallbackModel
                    );
                    variant.expiresAt = gameTime + directRefreshDelay(stack, slot);
                }
                return variant.visual;
            }
        }

        DirectArmorVariant next = new DirectArmorVariant(
                slot,
                stack.copy(),
                resolveDirectArmorVisual(stack, entity, context, cache, fallbackModel),
                gameTime + directRefreshDelay(stack, slot)
        );
        variants.add(next);
        directArmorVariantCount++;

        if (directArmorVariantCount > MAX_DIRECT_VISUAL_VARIANTS) {
            directArmorCache.clear();
            directArmorVariantCount = 0;
        }

        return next.visual;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DirectArmorVisual resolveDirectArmorVisual(ItemStack stack,
                                                        ArmorStand entity,
                                                        Object context,
                                                        RendererCache cache,
                                                        HumanoidModel<?> fallbackModel) throws Exception {
        Item item = stack.getItem();

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

        return new DirectArmorVisual(model, renderType, overlayRenderType);
    }

    private static long directRefreshDelay(ItemStack stack, EquipmentSlot slot) {
        int hash = System.identityHashCode(stack.getItem());
        hash = 31 * hash + stack.getDamageValue();
        hash = 31 * hash + (stack.hasTag() ? stack.getTag().hashCode() : 0);
        hash = 31 * hash + slot.ordinal();
        return DIRECT_VISUAL_CACHE_TICKS
                + (Integer.toUnsignedLong(hash) % DIRECT_VISUAL_CACHE_SPREAD_TICKS);
    }

    @SuppressWarnings("rawtypes")
    private static void renderDirectPiece(DirectArmorVisual visual,
                                          ItemStack stack,
                                          EquipmentSlot slot,
                                          HumanoidModel<?> parentModel,
                                          PoseStack poseStack,
                                          MultiBufferSource buffers,
                                          int packedLight) {
        HumanoidModel model = visual.model;

        // Palladium models are shared globally. Reapply the HeroStand's static parent pivots before
        // every draw so another entity render cannot leave this cached model in a different pose.
        ((HumanoidModel) parentModel).copyPropertiesTo(model);
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
        if (!FastHumanoidModelRenderer.render(
                model,
                poseStack,
                consumer,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                red, green, blue, 1.0F
        )) {
            model.renderToBuffer(
                    poseStack,
                    consumer,
                    packedLight,
                    OverlayTexture.NO_OVERLAY,
                    red, green, blue, 1.0F
            );
        }
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

    private void ensureArmorContexts(ArmorStand entity) throws Throwable {
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
        directArmorCache.clear();
        directArmorVariantCount = 0;
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            directScratch[i] = null;
        }
    }

    private void disableFastPath() {
        healthy = false;
        directArmorHealthy = false;
        rendererCache.clear();
        fastPathEligibility.clear();
        nativeRendererRequired.clear();
        clearDirectCache();
        staticLayers.resetSessionCache();
    }

    private record RendererCache(Object renderer, List<?> layers) {}

    private static final class DirectArmorVariant {
        final EquipmentSlot slot;
        final ItemStack stack;
        DirectArmorVisual visual;
        long expiresAt;

        DirectArmorVariant(EquipmentSlot slot,
                           ItemStack stack,
                           DirectArmorVisual visual,
                           long expiresAt) {
            this.slot = slot;
            this.stack = stack;
            this.visual = visual;
            this.expiresAt = expiresAt;
        }
    }

    @SuppressWarnings("rawtypes")
    private record DirectArmorVisual(
            HumanoidModel model,
            RenderType renderType,
            RenderType overlayRenderType
    ) {}
}
