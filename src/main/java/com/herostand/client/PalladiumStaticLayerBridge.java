package com.herostand.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static-display accelerator for common Palladium pack-layer types.
 *
 * Palladium's normal player renderer re-evaluates conditions, dynamic textures, model selectors
 * and colors every frame because players can animate and change state. HeroStand's render context
 * is static, so keep those expensive choices cached briefly while still emitting the actual model
 * vertices every frame. Unknown/dynamic layer types fall back to Palladium unchanged.
 */
final class PalladiumStaticLayerBridge {
    private static final long CACHE_TICKS = 40L;
    private static final long CACHE_SPREAD_TICKS = 20L;
    private static final int MAX_CACHED_VARIANTS = 2048;

    private final boolean installed;
    private boolean healthy;

    private final Class<?> packLayerClass;
    private final Class<?> skinOverlayLayerClass;
    private final Class<?> compoundLayerClass;
    private final Class<?> extraAnimatedModelClass;

    private final Field conditionsField;
    private final Field thirdPersonConditionsField;

    private final Field packModelLookupField;
    private final Field packModelField;
    private final Field packTextureField;
    private final Field packRenderTypeField;
    private final Field packTintField;
    private final Field packEnchantmentGlintField;

    private final Field skinTextureField;
    private final Field skinRenderTypeField;
    private final Field skinTintField;

    private final Field compoundLayersField;

    private final MethodHandle conditionActive;
    private final MethodHandle skinTypedGet;
    private final MethodHandle modelTypeFitsEntity;
    private final MethodHandle modelCacheGetModel;
    private final MethodHandle dynamicTextureGet;
    private final MethodHandle dynamicColorGet;
    private final MethodHandle renderTypeCreateVertexConsumer;
    private final MethodHandle renderTypeGetPackedLight;
    private final MethodHandle copyModelProperties;
    private final MethodHandle extraAnimations;

    private final Map<Object, LayerDescriptor> descriptorCache = new IdentityHashMap<>();
    private final Map<Object, List<CachedVariant>> visualCache = new IdentityHashMap<>();
    private int cachedVariantCount;

    PalladiumStaticLayerBridge() {
        boolean present = ModList.get().isLoaded("palladium");

        Class<?> pack = null;
        Class<?> skin = null;
        Class<?> compound = null;
        Class<?> extra = null;

        Field conditions = null;
        Field thirdPerson = null;

        Field packModelLookup = null;
        Field packModel = null;
        Field packTexture = null;
        Field packRenderType = null;
        Field packTint = null;
        Field packGlint = null;

        Field skinTexture = null;
        Field skinRenderType = null;
        Field skinTint = null;

        Field compoundLayers = null;

        MethodHandle condActive = null;
        MethodHandle skinGet = null;
        MethodHandle fitsEntity = null;
        MethodHandle cacheGetModel = null;
        MethodHandle textureGet = null;
        MethodHandle colorGet = null;
        MethodHandle createVertexConsumer = null;
        MethodHandle getPackedLight = null;
        MethodHandle copyProperties = null;
        MethodHandle extraAnim = null;

        if (present) {
            try {
                ClassLoader loader = PalladiumStaticLayerBridge.class.getClassLoader();
                MethodHandles.Lookup lookup = MethodHandles.lookup();

                Class<?> abstractLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.AbstractPackRenderLayer",
                        false, loader);
                pack = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayer",
                        false, loader);
                skin = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.SkinOverlayPackRenderLayer",
                        false, loader);
                compound = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.CompoundPackRenderLayer",
                        false, loader);
                Class<?> packInterface = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.IPackRenderLayer",
                        false, loader);
                Class<?> conditionClass = Class.forName(
                        "net.threetag.palladium.condition.Condition",
                        false, loader);
                Class<?> dataContextClass = Class.forName(
                        "net.threetag.palladium.util.context.DataContext",
                        false, loader);
                Class<?> skinTypedValueClass = Class.forName(
                        "net.threetag.palladium.util.SkinTypedValue",
                        false, loader);
                Class<?> modelTypeClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.ModelTypes$Model",
                        false, loader);
                Class<?> modelCacheClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayer$ModelCache",
                        false, loader);
                Class<?> dynamicTextureClass = Class.forName(
                        "net.threetag.palladium.client.dynamictexture.DynamicTexture",
                        false, loader);
                Class<?> dynamicColorClass = Class.forName(
                        "net.threetag.palladium.client.renderer.DynamicColor",
                        false, loader);
                Class<?> renderTypeFunctionClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.RenderTypeFunction",
                        false, loader);
                extra = Class.forName(
                        "net.threetag.palladium.client.model.ExtraAnimatedModel",
                        false, loader);

                conditions = accessible(abstractLayerClass.getDeclaredField("conditions"));
                thirdPerson = accessible(abstractLayerClass.getDeclaredField("thirdPersonConditions"));

                packModelLookup = accessible(pack.getDeclaredField("modelLookup"));
                packModel = accessible(pack.getDeclaredField("model"));
                packTexture = accessible(pack.getDeclaredField("texture"));
                packRenderType = accessible(pack.getDeclaredField("renderType"));
                packTint = accessible(pack.getDeclaredField("tint"));
                packGlint = accessible(pack.getDeclaredField("enchantmentGlint"));

                skinTexture = accessible(skin.getDeclaredField("texture"));
                skinRenderType = accessible(skin.getDeclaredField("renderType"));
                skinTint = accessible(skin.getDeclaredField("tint"));

                compoundLayers = accessible(compound.getDeclaredField("layers"));

                condActive = lookup.unreflect(conditionClass.getMethod("active", dataContextClass))
                        .asType(MethodType.methodType(
                                boolean.class, Object.class, Object.class));

                skinGet = lookup.unreflect(skinTypedValueClass.getMethod("get", Entity.class))
                        .asType(MethodType.methodType(
                                Object.class, Object.class, Entity.class));

                fitsEntity = lookup.unreflect(modelTypeClass.getMethod(
                                "fitsEntity", Entity.class, EntityModel.class))
                        .asType(MethodType.methodType(
                                boolean.class, Object.class, Entity.class, EntityModel.class));

                cacheGetModel = lookup.unreflect(modelCacheClass.getMethod(
                                "getModel", dataContextClass, modelTypeClass))
                        .asType(MethodType.methodType(
                                EntityModel.class, Object.class, Object.class, Object.class));

                textureGet = lookup.unreflect(dynamicTextureClass.getMethod(
                                "getTexture", dataContextClass))
                        .asType(MethodType.methodType(
                                ResourceLocation.class, Object.class, Object.class));

                colorGet = lookup.unreflect(dynamicColorClass.getMethod(
                                "getColor", dataContextClass))
                        .asType(MethodType.methodType(
                                Color.class, Object.class, Object.class));

                createVertexConsumer = lookup.unreflect(renderTypeFunctionClass.getMethod(
                                "createVertexConsumer", MultiBufferSource.class, ResourceLocation.class, boolean.class))
                        .asType(MethodType.methodType(
                                VertexConsumer.class,
                                Object.class, MultiBufferSource.class, ResourceLocation.class, boolean.class));

                getPackedLight = lookup.unreflect(renderTypeFunctionClass.getMethod(
                                "getPackedLight", int.class))
                        .asType(MethodType.methodType(
                                int.class, Object.class, int.class));

                copyProperties = lookup.unreflect(packInterface.getMethod(
                                "copyModelProperties", Entity.class, HumanoidModel.class, HumanoidModel.class))
                        .asType(MethodType.methodType(
                                void.class, Entity.class, HumanoidModel.class, HumanoidModel.class));

                extraAnim = lookup.unreflect(extra.getMethod(
                                "extraAnimations",
                                Entity.class,
                                float.class, float.class, float.class,
                                float.class, float.class, float.class))
                        .asType(MethodType.methodType(
                                void.class,
                                Object.class, Entity.class,
                                float.class, float.class, float.class,
                                float.class, float.class, float.class));
            } catch (Throwable ignored) {
                present = false;
            }
        }

        this.installed = present;
        this.healthy = present;

        this.packLayerClass = pack;
        this.skinOverlayLayerClass = skin;
        this.compoundLayerClass = compound;
        this.extraAnimatedModelClass = extra;

        this.conditionsField = conditions;
        this.thirdPersonConditionsField = thirdPerson;

        this.packModelLookupField = packModelLookup;
        this.packModelField = packModel;
        this.packTextureField = packTexture;
        this.packRenderTypeField = packRenderType;
        this.packTintField = packTint;
        this.packEnchantmentGlintField = packGlint;

        this.skinTextureField = skinTexture;
        this.skinRenderTypeField = skinRenderType;
        this.skinTintField = skinTint;

        this.compoundLayersField = compoundLayers;

        this.conditionActive = condActive;
        this.skinTypedGet = skinGet;
        this.modelTypeFitsEntity = fitsEntity;
        this.modelCacheGetModel = cacheGetModel;
        this.dynamicTextureGet = textureGet;
        this.dynamicColorGet = colorGet;
        this.renderTypeCreateVertexConsumer = createVertexConsumer;
        this.renderTypeGetPackedLight = getPackedLight;
        this.copyModelProperties = copyProperties;
        this.extraAnimations = extraAnim;
    }

    /**
     * @return true when this bridge fully handled the layer (including an intentionally inactive
     * cached layer); false when Palladium's original layer renderer should be used.
     */
    boolean renderIfSupported(Object layer,
                              Object context,
                              ArmorStand entity,
                              EntityModel<?> parentModel,
                              ItemStack stack,
                              EquipmentSlot slot,
                              PoseStack poseStack,
                              MultiBufferSource buffers,
                              int packedLight,
                              float partialTick,
                              long gameTime) {
        if (!installed || !healthy) return false;

        try {
            LayerDescriptor descriptor = descriptorFor(layer);
            if (descriptor == null || !descriptor.fullySupported) return false;

            renderDescriptor(
                    layer, descriptor, context, entity, parentModel, stack, slot,
                    poseStack, buffers, packedLight, partialTick, gameTime
            );
            return true;
        } catch (Throwable ignored) {
            disable();
            return false;
        }
    }

    void resetSessionCache() {
        descriptorCache.clear();
        visualCache.clear();
        cachedVariantCount = 0;
        healthy = installed;
    }

    private void renderDescriptor(Object layer,
                                  LayerDescriptor descriptor,
                                  Object context,
                                  ArmorStand entity,
                                  EntityModel<?> parentModel,
                                  ItemStack stack,
                                  EquipmentSlot slot,
                                  PoseStack poseStack,
                                  MultiBufferSource buffers,
                                  int packedLight,
                                  float partialTick,
                                  long gameTime) throws Throwable {
        if (descriptor instanceof PackDescriptor pack) {
            PackVisual visual = (PackVisual) cachedVisual(
                    layer, stack, slot, gameTime,
                    () -> resolvePack(pack, context, entity, parentModel, stack)
            );

            if (!visual.active) return;

            setupModelForDraw(
                    visual.model, entity, parentModel,
                    partialTick
            );

            VertexConsumer consumer = (VertexConsumer) renderTypeCreateVertexConsumer.invokeExact(
                    visual.renderType, buffers, visual.texture, visual.glint
            );
            int light = (int) renderTypeGetPackedLight.invokeExact(
                    visual.renderType, packedLight
            );

            if (visual.model instanceof HumanoidModel humanoid
                    && FastHumanoidModelRenderer.render(
                            humanoid,
                            poseStack,
                            consumer,
                            light,
                            OverlayTexture.NO_OVERLAY,
                            visual.red, visual.green, visual.blue, visual.alpha
                    )) {
                return;
            }

            @SuppressWarnings("rawtypes")
            EntityModel raw = visual.model;
            raw.renderToBuffer(
                    poseStack, consumer, light, OverlayTexture.NO_OVERLAY,
                    visual.red, visual.green, visual.blue, visual.alpha
            );
            return;
        }

        if (descriptor instanceof SkinDescriptor skin) {
            SkinVisual visual = (SkinVisual) cachedVisual(
                    layer, stack, slot, gameTime,
                    () -> resolveSkin(skin, context, entity, stack)
            );

            if (!visual.active) return;

            VertexConsumer consumer = (VertexConsumer) renderTypeCreateVertexConsumer.invokeExact(
                    visual.renderType, buffers, visual.texture, visual.glint
            );
            int light = (int) renderTypeGetPackedLight.invokeExact(
                    visual.renderType, packedLight
            );

            if (parentModel instanceof HumanoidModel humanoid
                    && FastHumanoidModelRenderer.render(
                            humanoid,
                            poseStack,
                            consumer,
                            light,
                            OverlayTexture.NO_OVERLAY,
                            visual.red, visual.green, visual.blue, visual.alpha
                    )) {
                return;
            }

            @SuppressWarnings("rawtypes")
            EntityModel rawParent = parentModel;
            rawParent.renderToBuffer(
                    poseStack, consumer, light, OverlayTexture.NO_OVERLAY,
                    visual.red, visual.green, visual.blue, visual.alpha
            );
            return;
        }

        if (descriptor instanceof CompoundDescriptor compound) {
            ActiveVisual visual = (ActiveVisual) cachedVisual(
                    layer, stack, slot, gameTime,
                    () -> new ActiveVisual(conditionsMet(compound.conditions, context)
                            && conditionsMet(compound.thirdPersonConditions, context))
            );

            if (!visual.active) return;

            for (int i = 0; i < compound.layers.size(); i++) {
                Object child = compound.layers.get(i);
                LayerDescriptor childDescriptor = compound.childDescriptors.get(i);
                renderDescriptor(
                        child, childDescriptor, context, entity, parentModel, stack, slot,
                        poseStack, buffers, packedLight, partialTick, gameTime
                );
            }
        }
    }

    private PackVisual resolvePack(PackDescriptor descriptor,
                                   Object context,
                                   ArmorStand entity,
                                   EntityModel<?> parentModel,
                                   ItemStack stack) throws Throwable {
        if (!conditionsMet(descriptor.conditions, context)
                || !conditionsMet(descriptor.thirdPersonConditions, context)) {
            return PackVisual.INACTIVE;
        }

        Object modelType = (Object) skinTypedGet.invokeExact(
                descriptor.modelLookup, (Entity) entity
        );

        boolean fits = (boolean) modelTypeFitsEntity.invokeExact(
                modelType, (Entity) entity, parentModel
        );
        if (!fits) return PackVisual.INACTIVE;

        Object modelCache = (Object) skinTypedGet.invokeExact(
                descriptor.model, (Entity) entity
        );

        EntityModel<?> model = (EntityModel<?>) modelCacheGetModel.invokeExact(
                modelCache, context, modelType
        );

        Object dynamicTexture = (Object) skinTypedGet.invokeExact(
                descriptor.texture, (Entity) entity
        );

        ResourceLocation texture = (ResourceLocation) dynamicTextureGet.invokeExact(
                dynamicTexture, context
        );

        boolean glint = stack.hasFoil() || conditionsMet(descriptor.enchantmentGlint, context);

        Color tint = descriptor.tint == null
                ? Color.WHITE
                : (Color) dynamicColorGet.invokeExact(descriptor.tint, context);

        return new PackVisual(
                true,
                model,
                texture,
                descriptor.renderType,
                glint,
                tint.getRed() / 255.0F,
                tint.getGreen() / 255.0F,
                tint.getBlue() / 255.0F,
                tint.getAlpha() / 255.0F
        );
    }

    private SkinVisual resolveSkin(SkinDescriptor descriptor,
                                   Object context,
                                   ArmorStand entity,
                                   ItemStack stack) throws Throwable {
        if (!conditionsMet(descriptor.conditions, context)
                || !conditionsMet(descriptor.thirdPersonConditions, context)) {
            return SkinVisual.INACTIVE;
        }

        Object dynamicTexture = (Object) skinTypedGet.invokeExact(
                descriptor.texture, (Entity) entity
        );

        ResourceLocation texture = (ResourceLocation) dynamicTextureGet.invokeExact(
                dynamicTexture, context
        );

        Color tint = descriptor.tint == null
                ? Color.WHITE
                : (Color) dynamicColorGet.invokeExact(descriptor.tint, context);

        return new SkinVisual(
                true,
                texture,
                descriptor.renderType,
                stack.hasFoil(),
                tint.getRed() / 255.0F,
                tint.getGreen() / 255.0F,
                tint.getBlue() / 255.0F,
                tint.getAlpha() / 255.0F
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setupModelForDraw(EntityModel<?> model,
                                   ArmorStand entity,
                                   EntityModel<?> parentModel,
                                   float partialTick) throws Throwable {
        if (model instanceof HumanoidModel child && parentModel instanceof HumanoidModel parent) {
            copyModelProperties.invokeExact(
                    (Entity) entity,
                    parent,
                    child
            );
        } else {
            EntityModel raw = model;
            raw.prepareMobModel(entity, 0.0F, 0.0F, partialTick);
            raw.setupAnim(entity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }

        if (extraAnimatedModelClass.isInstance(model)) {
            extraAnimations.invokeExact(
                    (Object) model,
                    (Entity) entity,
                    0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, partialTick
            );
        }
    }

    private boolean conditionsMet(List<?> conditions, Object context) throws Throwable {
        for (Object condition : conditions) {
            boolean active = (boolean) conditionActive.invokeExact(condition, context);
            if (!active) return false;
        }
        return true;
    }

    private LayerDescriptor descriptorFor(Object layer) throws ReflectiveOperationException {
        if (descriptorCache.containsKey(layer)) {
            return descriptorCache.get(layer);
        }

        LayerDescriptor descriptor = buildDescriptor(layer);
        descriptorCache.put(layer, descriptor);
        return descriptor;
    }

    private LayerDescriptor buildDescriptor(Object layer) throws ReflectiveOperationException {
        if (packLayerClass.isInstance(layer)) {
            return new PackDescriptor(
                    (List<?>) conditionsField.get(layer),
                    (List<?>) thirdPersonConditionsField.get(layer),
                    packModelLookupField.get(layer),
                    packModelField.get(layer),
                    packTextureField.get(layer),
                    packRenderTypeField.get(layer),
                    packTintField.get(layer),
                    (List<?>) packEnchantmentGlintField.get(layer)
            );
        }

        if (skinOverlayLayerClass.isInstance(layer)) {
            return new SkinDescriptor(
                    (List<?>) conditionsField.get(layer),
                    (List<?>) thirdPersonConditionsField.get(layer),
                    skinTextureField.get(layer),
                    skinRenderTypeField.get(layer),
                    skinTintField.get(layer)
            );
        }

        if (compoundLayerClass.isInstance(layer)) {
            List<?> children = (List<?>) compoundLayersField.get(layer);
            List<LayerDescriptor> childDescriptors = new ArrayList<>(children.size());
            boolean fullySupported = true;

            for (Object child : children) {
                LayerDescriptor childDescriptor = descriptorFor(child);
                childDescriptors.add(childDescriptor);
                if (childDescriptor == null || !childDescriptor.fullySupported) {
                    fullySupported = false;
                }
            }

            return new CompoundDescriptor(
                    (List<?>) conditionsField.get(layer),
                    (List<?>) thirdPersonConditionsField.get(layer),
                    children,
                    childDescriptors,
                    fullySupported
            );
        }

        return null;
    }

    private Object cachedVisual(Object layer,
                                ItemStack stack,
                                EquipmentSlot slot,
                                long gameTime,
                                VisualResolver resolver) throws Throwable {
        List<CachedVariant> variants = visualCache.computeIfAbsent(
                layer, ignored -> new ArrayList<>(2)
        );

        for (CachedVariant variant : variants) {
            if (variant.slot == slot && ItemStack.isSameItemSameTags(variant.stack, stack)) {
                if (gameTime < variant.expiresAt) {
                    return variant.visual;
                }

                variant.visual = resolver.resolve();
                variant.expiresAt = gameTime + refreshDelay(stack, slot);
                return variant.visual;
            }
        }

        CachedVariant next = new CachedVariant(
                slot,
                stack.copy(),
                resolver.resolve(),
                gameTime + refreshDelay(stack, slot)
        );
        variants.add(next);
        cachedVariantCount++;

        if (cachedVariantCount > MAX_CACHED_VARIANTS) {
            visualCache.clear();
            cachedVariantCount = 0;
        }

        return next.visual;
    }

    private static long refreshDelay(ItemStack stack, EquipmentSlot slot) {
        int hash = System.identityHashCode(stack.getItem());
        hash = 31 * hash + stack.getDamageValue();
        hash = 31 * hash + (stack.hasTag() ? stack.getTag().hashCode() : 0);
        hash = 31 * hash + slot.ordinal();

        long nonNegative = Integer.toUnsignedLong(hash);
        return CACHE_TICKS + (nonNegative % CACHE_SPREAD_TICKS);
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private void disable() {
        healthy = false;
        descriptorCache.clear();
        visualCache.clear();
        cachedVariantCount = 0;
    }

    private interface VisualResolver {
        Object resolve() throws Throwable;
    }

    private abstract static class LayerDescriptor {
        final boolean fullySupported;

        LayerDescriptor(boolean fullySupported) {
            this.fullySupported = fullySupported;
        }
    }

    private static final class PackDescriptor extends LayerDescriptor {
        final List<?> conditions;
        final List<?> thirdPersonConditions;
        final Object modelLookup;
        final Object model;
        final Object texture;
        final Object renderType;
        final Object tint;
        final List<?> enchantmentGlint;

        PackDescriptor(List<?> conditions,
                       List<?> thirdPersonConditions,
                       Object modelLookup,
                       Object model,
                       Object texture,
                       Object renderType,
                       Object tint,
                       List<?> enchantmentGlint) {
            super(true);
            this.conditions = conditions;
            this.thirdPersonConditions = thirdPersonConditions;
            this.modelLookup = modelLookup;
            this.model = model;
            this.texture = texture;
            this.renderType = renderType;
            this.tint = tint;
            this.enchantmentGlint = enchantmentGlint;
        }
    }

    private static final class SkinDescriptor extends LayerDescriptor {
        final List<?> conditions;
        final List<?> thirdPersonConditions;
        final Object texture;
        final Object renderType;
        final Object tint;

        SkinDescriptor(List<?> conditions,
                       List<?> thirdPersonConditions,
                       Object texture,
                       Object renderType,
                       Object tint) {
            super(true);
            this.conditions = conditions;
            this.thirdPersonConditions = thirdPersonConditions;
            this.texture = texture;
            this.renderType = renderType;
            this.tint = tint;
        }
    }

    private static final class CompoundDescriptor extends LayerDescriptor {
        final List<?> conditions;
        final List<?> thirdPersonConditions;
        final List<?> layers;
        final List<LayerDescriptor> childDescriptors;

        CompoundDescriptor(List<?> conditions,
                           List<?> thirdPersonConditions,
                           List<?> layers,
                           List<LayerDescriptor> childDescriptors,
                           boolean fullySupported) {
            super(fullySupported);
            this.conditions = conditions;
            this.thirdPersonConditions = thirdPersonConditions;
            this.layers = layers;
            this.childDescriptors = childDescriptors;
        }
    }

    private static final class CachedVariant {
        final EquipmentSlot slot;
        final ItemStack stack;
        Object visual;
        long expiresAt;

        CachedVariant(EquipmentSlot slot, ItemStack stack, Object visual, long expiresAt) {
            this.slot = slot;
            this.stack = stack;
            this.visual = visual;
            this.expiresAt = expiresAt;
        }
    }

    private record ActiveVisual(boolean active) {}

    private record PackVisual(
            boolean active,
            EntityModel<?> model,
            ResourceLocation texture,
            Object renderType,
            boolean glint,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        static final PackVisual INACTIVE =
                new PackVisual(false, null, null, null, false, 1, 1, 1, 1);
    }

    private record SkinVisual(
            boolean active,
            ResourceLocation texture,
            Object renderType,
            boolean glint,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        static final SkinVisual INACTIVE =
                new SkinVisual(false, null, null, false, 1, 1, 1, 1);
    }
}
