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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class PalladiumNativeBridge {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String PACK_LAYER_RENDERER =
            "net.threetag.palladium.client.renderer.renderlayer.PackRenderLayerRenderer";
    private static final Rotations ZERO = new Rotations(0.0F, 0.0F, 0.0F);

    private final boolean installed;
    private boolean healthy;
    private final Constructor<?> suitStandConstructor;
    private final Class<?> armorWithRendererClass;
    private final Class<?> armorRendererDataClass;
    private final Class<?> extraAnimatedModelClass;
    private final Method getCachedArmorRenderer;
    private final Method getArmorModel;
    private final Method forArmorInSlot;
    private final Field livingRendererLayersField;
    private final Map<Item, Object> rendererCache = new IdentityHashMap<>();

    private ArmorStand context;
    private Level contextLevel;

    @SuppressWarnings("rawtypes")
    private EntityModel parentModel;
    @SuppressWarnings("rawtypes")
    private RenderLayer armorLayer;
    @SuppressWarnings("rawtypes")
    private RenderLayer packLayer;

    PalladiumNativeBridge() {
        boolean present = ModList.get().isLoaded("palladium");
        Constructor<?> suitCtor = null;
        Class<?> armorInterface = null;
        Class<?> rendererData = null;
        Class<?> extraAnimated = null;
        Method cachedRenderer = null;
        Method armorModel = null;
        Method armorContext = null;
        Field layers = null;

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
                extraAnimated = Class.forName(
                        "net.threetag.palladium.client.model.ExtraAnimatedModel", false, loader);
                Class<?> dataContextClass = Class.forName(
                        "net.threetag.palladium.util.context.DataContext", false, loader);

                suitCtor = suitStandClass.getConstructor(
                        Level.class, double.class, double.class, double.class);
                cachedRenderer = armorInterface.getMethod("getCachedArmorRenderer");
                armorModel = rendererData.getMethod(
                        "getModel", LivingEntity.class, dataContextClass);
                armorContext = dataContextClass.getMethod(
                        "forArmorInSlot", LivingEntity.class, EquipmentSlot.class);

                for (Field candidate : LivingEntityRenderer.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(candidate.getType())) {
                        candidate.setAccessible(true);
                        layers = candidate;
                        break;
                    }
                }
                if (layers == null) throw new IllegalStateException("renderer layers unavailable");
            } catch (Throwable ignored) {
                present = false;
            }
        }

        installed = present;
        healthy = present;
        suitStandConstructor = suitCtor;
        armorWithRendererClass = armorInterface;
        armorRendererDataClass = rendererData;
        extraAnimatedModelClass = extraAnimated;
        getCachedArmorRenderer = cachedRenderer;
        getArmorModel = armorModel;
        forArmorInSlot = armorContext;
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
                if (!armorWithRendererClass.isInstance(stack.getItem())) return false;
                if (rendererFor(stack.getItem()) == null) return false;
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
                Object created = suitStandConstructor.newInstance(level, 0.0D, 0.0D, 0.0D);
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
            }

            copyEquipment(stand, context);
            pinRotation(context);
            context.tickCount = (int) (level.getGameTime() & 0x7FFFFFFFL);
            return context;
        } catch (Throwable failure) {
            disable();
            return null;
        }
    }

    boolean isBaseArmorSnapshotSafe(ArmorStand suitStand) {
        if (!isInstalled() || suitStand == null) return false;
        try {
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                ItemStack stack = suitStand.getItemBySlot(slot);
                if (stack.isEmpty()) continue;
                Object renderer = rendererFor(stack.getItem());
                if (renderer == null) return false;
                Object dataContext = forArmorInSlot.invoke(null, suitStand, slot);
                Object model = getArmorModel.invoke(renderer, suitStand, dataContext);
                if (model != null && extraAnimatedModelClass.isInstance(model)) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    boolean renderBaseArmor(ArmorStand suitStand, PoseStack poseStack,
                            MultiBufferSource buffers, int packedLight, float partialTick) {
        if (!isInstalled() || armorLayer == null || parentModel == null) return false;
        try {
            prepareParentModel(suitStand, partialTick);
            poseStack.pushPose();
            try {
                applySuitStandTransform(suitStand, poseStack);
                armorLayer.render(
                        poseStack, buffers, packedLight, suitStand,
                        0.0F, 0.0F, partialTick,
                        suitStand.tickCount + partialTick, 0.0F, 0.0F);
            } finally {
                poseStack.popPose();
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    boolean renderLivePackLayers(ArmorStand suitStand, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight, float partialTick) {
        if (!isInstalled() || packLayer == null || parentModel == null) return false;
        try {
            prepareParentModel(suitStand, partialTick);
            poseStack.pushPose();
            try {
                applySuitStandTransform(suitStand, poseStack);
                packLayer.render(
                        poseStack, buffers, packedLight, suitStand,
                        0.0F, 0.0F, partialTick,
                        suitStand.tickCount + partialTick, 0.0F, 0.0F);
            } finally {
                poseStack.popPose();
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    void reset() {
        rendererCache.clear();
        context = null;
        contextLevel = null;
        parentModel = null;
        armorLayer = null;
        packLayer = null;
        healthy = installed;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean resolveNativeLayers(ArmorStand suitStand) throws Exception {
        EntityRenderer<?> raw = Minecraft.getInstance()
                .getEntityRenderDispatcher().getRenderer(suitStand);
        if (!(raw instanceof LivingEntityRenderer renderer)) return false;

        Object value = livingRendererLayersField.get(renderer);
        if (!(value instanceof List<?> layers)) return false;

        RenderLayer foundArmor = null;
        RenderLayer foundPack = null;
        for (Object layer : layers) {
            if (layer instanceof HumanoidArmorLayer) {
                foundArmor = (RenderLayer) layer;
            } else if (layer != null
                    && PACK_LAYER_RENDERER.equals(layer.getClass().getName())) {
                foundPack = (RenderLayer) layer;
            }
        }
        if (foundArmor == null || foundPack == null) return false;

        parentModel = renderer.getModel();
        armorLayer = foundArmor;
        packLayer = foundPack;
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void prepareParentModel(ArmorStand suitStand, float partialTick) {
        parentModel.attackTime = 0.0F;
        parentModel.riding = false;
        parentModel.young = false;
        parentModel.prepareMobModel(suitStand, 0.0F, 0.0F, partialTick);
        parentModel.setupAnim(
                suitStand, 0.0F, 0.0F,
                suitStand.tickCount + partialTick, 0.0F, 0.0F);
    }

    private static void applySuitStandTransform(ArmorStand stand, PoseStack poseStack) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
        if (!stand.isNoBasePlate()) poseStack.translate(0.0D, -1.0D / 16.0D, 0.0D);
        poseStack.translate(0.0D, -1.501D, 0.0D);
    }

    private Object rendererFor(Item item) throws Exception {
        if (rendererCache.containsKey(item)) return rendererCache.get(item);
        Object renderer = getCachedArmorRenderer.invoke(item);
        if (renderer == null || !armorRendererDataClass.isInstance(renderer)) {
            rendererCache.put(item, null);
            return null;
        }
        rendererCache.put(item, renderer);
        return renderer;
    }

    private static void copyEquipment(HeroStandBlockEntity stand, ArmorStand context) {
        context.setItemSlot(EquipmentSlot.HEAD, stand.getArmor(HeroStandBlockEntity.HEAD));
        context.setItemSlot(EquipmentSlot.CHEST, stand.getArmor(HeroStandBlockEntity.CHEST));
        context.setItemSlot(EquipmentSlot.LEGS, stand.getArmor(HeroStandBlockEntity.LEGS));
        context.setItemSlot(EquipmentSlot.FEET, stand.getArmor(HeroStandBlockEntity.FEET));
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
        reset();
        healthy = false;
    }
}
