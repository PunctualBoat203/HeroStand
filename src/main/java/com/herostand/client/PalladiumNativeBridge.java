package com.herostand.client;

import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.Rotations;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Stable Palladium path for HeroStand 0.2.9.
 *
 * No probing renders, no custom VBOs, no texture readback, no custom translucent sorting.
 * Every Palladium/Satsu layer is rendered exactly once.
 *
 * Optimization: Palladium normally rediscovers its complete render-layer graph every frame.
 * HeroStand displays are static, so cache that (DataContext, IPackRenderLayer) plan per stand
 * until its armor changes, and reuse one entity-only DataContext for all condition checks.
 */
final class PalladiumNativeBridge {
    private static final Rotations ZERO = new Rotations(0.0F, 0.0F, 0.0F);

    private final boolean installed;
    private boolean healthy;

    private final Constructor<?> suitStandConstructor;
    private final Class<?> armorWithRendererClass;
    private final Class<?> armorRendererDataClass;
    private final Method getCachedArmorRenderer;
    private final Method forEachPackLayer;
    private final Method packLayerRender;
    private final Method dataContextForEntity;
    private final Field livingRendererLayersField;

    private final Map<Item, Object> rendererCache = new IdentityHashMap<>();
    private final WeakHashMap<HeroStandBlockEntity, LayerPlan> renderPlans =
            new WeakHashMap<>();

    private ArmorStand context;
    private Level contextLevel;
    private Object entityConditionContext;

    @SuppressWarnings("rawtypes")
    private EntityModel parentModel;
    @SuppressWarnings("rawtypes")
    private RenderLayer armorLayer;

    private long planHits;
    private long planBuilds;
    private long layerCalls;

    PalladiumNativeBridge() {
        boolean present = ModList.get().isLoaded("palladium");

        Constructor<?> suitCtor = null;
        Class<?> armorInterface = null;
        Class<?> rendererData = null;
        Method cachedRenderer = null;
        Method eachLayer = null;
        Method layerRender = null;
        Method forEntity = null;
        Field layers = null;

        if (present) {
            try {
                ClassLoader loader = PalladiumNativeBridge.class.getClassLoader();

                Class<?> suitStandClass = Class.forName(
                        "net.threetag.palladium.entity.SuitStand",
                        false,
                        loader
                );
                armorInterface = Class.forName(
                        "net.threetag.palladium.item.ArmorWithRenderer",
                        false,
                        loader
                );
                rendererData = Class.forName(
                        "net.threetag.palladium.client.renderer.item.armor.ArmorRendererData",
                        false,
                        loader
                );
                Class<?> dataContextClass = Class.forName(
                        "net.threetag.palladium.util.context.DataContext",
                        false,
                        loader
                );
                Class<?> packLayerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.IPackRenderLayer",
                        false,
                        loader
                );
                Class<?> managerClass = Class.forName(
                        "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayerManager",
                        false,
                        loader
                );

                suitCtor = suitStandClass.getConstructor(
                        Level.class,
                        double.class,
                        double.class,
                        double.class
                );
                cachedRenderer = armorInterface.getMethod("getCachedArmorRenderer");
                eachLayer = managerClass.getMethod(
                        "forEachLayer",
                        Entity.class,
                        BiConsumer.class
                );
                layerRender = packLayerClass.getMethod(
                        "render",
                        dataContextClass,
                        PoseStack.class,
                        MultiBufferSource.class,
                        EntityModel.class,
                        int.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class,
                        float.class
                );
                forEntity = dataContextClass.getMethod(
                        "forEntity",
                        Entity.class
                );

                // Production Forge/SRG may rename fields. Find the renderer layer list by type.
                for (Field candidate : LivingEntityRenderer.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(candidate.getType())) {
                        candidate.setAccessible(true);
                        layers = candidate;
                        break;
                    }
                }

                if (layers == null) {
                    throw new IllegalStateException("LivingEntityRenderer layer list unavailable");
                }
            } catch (Throwable ignored) {
                present = false;
            }
        }

        installed = present;
        healthy = present;
        suitStandConstructor = suitCtor;
        armorWithRendererClass = armorInterface;
        armorRendererDataClass = rendererData;
        getCachedArmorRenderer = cachedRenderer;
        forEachPackLayer = eachLayer;
        packLayerRender = layerRender;
        dataContextForEntity = forEntity;
        livingRendererLayersField = layers;
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
                        level,
                        0.0D,
                        0.0D,
                        0.0D
                );

                if (!(created instanceof ArmorStand armorStand)) {
                    disable();
                    return null;
                }

                context = armorStand;
                contextLevel = level;
                context.setInvisible(true);
                context.setNoBasePlate(false);
                context.setShowArms(true);

                context.setHeadPose(ZERO);
                context.setBodyPose(ZERO);
                context.setLeftArmPose(ZERO);
                context.setRightArmPose(ZERO);
                context.setLeftLegPose(ZERO);
                context.setRightLegPose(ZERO);

                if (!resolveNativeLayers(context)) {
                    disable();
                    return null;
                }

                entityConditionContext =
                        dataContextForEntity.invoke(null, context);

                // Plans contain contexts tied to this reusable SuitStand instance.
                renderPlans.clear();
            }

            copyEquipment(stand, context);
            pinRotation(context);

            // Preserve Palladium/Gecko timing exactly as the live renderer expects.
            context.tickCount = (int) (level.getGameTime() & 0x7FFFFFFFL);

            return context;
        } catch (Throwable failure) {
            disable();
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    boolean renderBaseArmor(ArmorStand suitStand,
                            PoseStack poseStack,
                            MultiBufferSource buffers,
                            int packedLight,
                            float partialTick) {
        if (!isInstalled()
                || suitStand == null
                || armorLayer == null
                || parentModel == null) {
            return false;
        }

        try {
            prepareParentModel(suitStand, partialTick);

            poseStack.pushPose();
            try {
                applySuitStandTransform(suitStand, poseStack);

                armorLayer.render(
                        poseStack,
                        buffers,
                        packedLight,
                        suitStand,
                        0.0F,
                        0.0F,
                        partialTick,
                        suitStand.tickCount + partialTick,
                        0.0F,
                        0.0F
                );
            } finally {
                poseStack.popPose();
            }

            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Render Palladium's exact discovered layer list, but skip PackRenderLayerManager.forEachLayer
     * on steady frames. No layer is duplicated, probed, frozen, or replaced.
     */
    boolean renderPackLayers(HeroStandBlockEntity stand,
                             ArmorStand suitStand,
                             PoseStack poseStack,
                             MultiBufferSource buffers,
                             int packedLight,
                             float partialTick) {
        if (!isInstalled()
                || suitStand == null
                || parentModel == null
                || entityConditionContext == null) {
            return false;
        }

        try {
            LayerPlan plan = renderPlans.get(stand);
            int revision = stand.renderRevision();

            if (plan == null || plan.revision != revision) {
                plan = buildPlan(suitStand, revision);
                renderPlans.put(stand, plan);
                planBuilds++;
            } else {
                planHits++;
            }

            prepareParentModel(suitStand, partialTick);

            poseStack.pushPose();
            PalladiumConditionContext.begin(entityConditionContext);
            try {
                applySuitStandTransform(suitStand, poseStack);

                for (LayerEntry entry : plan.entries) {
                    packLayerRender.invoke(
                            entry.layer,
                            entry.dataContext,
                            poseStack,
                            buffers,
                            parentModel,
                            packedLight,
                            0.0F,
                            0.0F,
                            partialTick,
                            suitStand.tickCount + partialTick,
                            0.0F,
                            0.0F
                    );
                    layerCalls++;
                }
            } finally {
                PalladiumConditionContext.end();
                poseStack.popPose();
            }

            return true;
        } catch (Throwable failure) {
            renderPlans.remove(stand);
            PalladiumConditionContext.end();
            return false;
        }
    }

    long planHits() {
        return planHits;
    }

    long planBuilds() {
        return planBuilds;
    }

    long layerCalls() {
        return layerCalls;
    }

    int planCount() {
        return renderPlans.size();
    }

    int plannedLayerCount() {
        int total = 0;
        for (LayerPlan plan : renderPlans.values()) {
            total += plan.entries.size();
        }
        return total;
    }

    void reset() {
        rendererCache.clear();
        renderPlans.clear();

        context = null;
        contextLevel = null;
        entityConditionContext = null;
        parentModel = null;
        armorLayer = null;

        planHits = 0L;
        planBuilds = 0L;
        layerCalls = 0L;

        healthy = installed;
    }

    private LayerPlan buildPlan(ArmorStand suitStand,
                                int revision) throws Exception {
        List<LayerEntry> entries = new ArrayList<>();

        @SuppressWarnings("unchecked")
        BiConsumer<Object, Object> collector =
                (dataContext, layer) ->
                        entries.add(new LayerEntry(dataContext, layer));

        forEachPackLayer.invoke(null, suitStand, collector);

        return new LayerPlan(revision, List.copyOf(entries));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean resolveNativeLayers(ArmorStand suitStand)
            throws Exception {
        EntityRenderer<?> rawRenderer =
                Minecraft.getInstance()
                        .getEntityRenderDispatcher()
                        .getRenderer(suitStand);

        if (!(rawRenderer instanceof LivingEntityRenderer renderer)) {
            return false;
        }

        Object rawLayers = livingRendererLayersField.get(renderer);

        if (!(rawLayers instanceof List<?> layers)) {
            return false;
        }

        RenderLayer foundArmor = null;

        for (Object layer : layers) {
            if (layer instanceof HumanoidArmorLayer) {
                foundArmor = (RenderLayer) layer;
                break;
            }
        }

        if (foundArmor == null) {
            return false;
        }

        parentModel = renderer.getModel();
        armorLayer = foundArmor;
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void prepareParentModel(ArmorStand suitStand,
                                    float partialTick) {
        parentModel.attackTime = 0.0F;
        parentModel.riding = false;
        parentModel.young = false;

        parentModel.prepareMobModel(
                suitStand,
                0.0F,
                0.0F,
                partialTick
        );

        parentModel.setupAnim(
                suitStand,
                0.0F,
                0.0F,
                suitStand.tickCount + partialTick,
                0.0F,
                0.0F
        );
    }

    private static void applySuitStandTransform(ArmorStand stand,
                                                PoseStack poseStack) {
        // Mirrors LivingEntityRenderer + Palladium SuitStandRenderer for the pinned stand.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.scale(-1.0F, -1.0F, 1.0F);

        float scale = 0.9375F;
        poseStack.scale(scale, scale, scale);

        if (!stand.isNoBasePlate()) {
            poseStack.translate(0.0D, -1.0D / 16.0D, 0.0D);
        }

        poseStack.translate(0.0D, -1.501D, 0.0D);
    }

    private Object rendererFor(Item item) throws Exception {
        if (rendererCache.containsKey(item)) {
            return rendererCache.get(item);
        }

        Object renderer = getCachedArmorRenderer.invoke(item);

        if (renderer == null
                || !armorRendererDataClass.isInstance(renderer)) {
            rendererCache.put(item, null);
            return null;
        }

        rendererCache.put(item, renderer);
        return renderer;
    }

    private static void copyEquipment(HeroStandBlockEntity stand,
                                      ArmorStand context) {
        context.setItemSlot(
                EquipmentSlot.HEAD,
                stand.getArmor(HeroStandBlockEntity.HEAD)
        );
        context.setItemSlot(
                EquipmentSlot.CHEST,
                stand.getArmor(HeroStandBlockEntity.CHEST)
        );
        context.setItemSlot(
                EquipmentSlot.LEGS,
                stand.getArmor(HeroStandBlockEntity.LEGS)
        );
        context.setItemSlot(
                EquipmentSlot.FEET,
                stand.getArmor(HeroStandBlockEntity.FEET)
        );
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
        renderPlans.clear();

        context = null;
        contextLevel = null;
        entityConditionContext = null;
        parentModel = null;
        armorLayer = null;
    }

    private record LayerEntry(Object dataContext, Object layer) {}

    private record LayerPlan(int revision, List<LayerEntry> entries) {}
}
