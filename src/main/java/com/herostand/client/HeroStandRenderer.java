package com.herostand.client;

import com.herostand.config.HeroStandClientConfig;
import com.herostand.config.HeroStandServerConfig;
import com.herostand.world.HeroStandBlock;
import com.herostand.world.HeroStandBlockEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmorStandArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class HeroStandRenderer implements BlockEntityRenderer<HeroStandBlockEntity> {
    private static final Set<HeroStandRenderer> ACTIVE_RENDERERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    /*
     * Distance and wall occlusion are already proven useful by user testing. Keep those rules
     * independent from the GPU backend so a backend soft-failure never disables culling.
     */
    private static final long VISIBLE_REFRESH_BASE_TICKS = 16L;
    private static final long VISIBLE_REFRESH_SPREAD_TICKS = 12L;
    private static final long OCCLUDED_REFRESH_BASE_TICKS = 10L;
    private static final long OCCLUDED_REFRESH_SPREAD_TICKS = 6L;
    private static final long MOVING_REFRESH_TICKS = 2L;
    private static final double CAMERA_MOVE_REFRESH_SQR = 0.50D * 0.50D;

    // Used only by HeroStand's two manual Palladium paths. The native compatibility backend lets
    // Palladium's SuitStandRenderer apply these transforms itself.
    private static final float PALLADIUM_SUIT_SCALE = 0.9375F;
    private static final double PALLADIUM_SUIT_Y_OFFSET = -0.0625D;

    private static final Vec3[] VISIBILITY_SAMPLES = {
            new Vec3(0.50D, 1.20D, 0.50D),
            new Vec3(0.50D, 1.75D, 0.50D),
            new Vec3(0.50D, 0.60D, 0.50D),
            new Vec3(0.22D, 1.20D, 0.50D),
            new Vec3(0.78D, 1.20D, 0.50D),
            new Vec3(0.50D, 1.20D, 0.22D),
            new Vec3(0.50D, 1.20D, 0.78D)
    };

    /**
     * The manual Palladium paths use normal humanoid pivots because Palladium's
     * SuitStandBasePlateModel is built from HumanoidModel.createMesh(...), not
     * ArmorStandArmorModel's shifted pivots.
     */
    private final HumanoidModel<ArmorStand> parentModel;
    private final ArmorStandArmorModel innerArmorModel;
    private final ArmorStandArmorModel outerArmorModel;
    private final HumanoidArmorLayer<ArmorStand, HumanoidModel<ArmorStand>, ArmorStandArmorModel> armorLayer;

    private final PalladiumRenderBridge palladium = new PalladiumRenderBridge();
    private final PalladiumSuitStandBridge palladiumSuitStand = new PalladiumSuitStandBridge();
    private final GpuRenderRouter gpuRouter = new GpuRenderRouter();
    private StaticArmorVboCache staticArmorCache;

    private final Map<Long, OcclusionEntry> occlusionCache = new HashMap<>();
    private Level occlusionLevel;

    private ArmorStand preparedPalladiumContext;
    private ArmorStand fallbackRenderContext;

    public HeroStandRenderer(BlockEntityRendererProvider.Context context) {
        this.parentModel = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER));

        RenderLayerParent<ArmorStand, HumanoidModel<ArmorStand>> parent =
                new RenderLayerParent<>() {
                    @Override
                    public HumanoidModel<ArmorStand> getModel() {
                        return parentModel;
                    }

                    @Override
                    public ResourceLocation getTextureLocation(ArmorStand entity) {
                        return ArmorStandRenderer.DEFAULT_SKIN_LOCATION;
                    }
                };

        this.innerArmorModel =
                new ArmorStandArmorModel(context.bakeLayer(ModelLayers.ARMOR_STAND_INNER_ARMOR));
        this.outerArmorModel =
                new ArmorStandArmorModel(context.bakeLayer(ModelLayers.ARMOR_STAND_OUTER_ARMOR));

        this.armorLayer = new HumanoidArmorLayer<>(
                parent,
                innerArmorModel,
                outerArmorModel,
                Minecraft.getInstance().getModelManager()
        );

        resetParentModel();

        synchronized (ACTIVE_RENDERERS) {
            ACTIVE_RENDERERS.add(this);
        }
    }

    /**
     * Called from the Forge client tick so idle VBOs are retired even while every HeroStand is
     * outside render distance and renderOrBuild(...) is not running.
     */
    static void tickGpuCaches(long gameTime) {
        synchronized (ACTIVE_RENDERERS) {
            for (HeroStandRenderer renderer : ACTIVE_RENDERERS) {
                if (renderer.staticArmorCache != null) {
                    renderer.staticArmorCache.tick(gameTime);
                }
            }
        }
    }

    /**
     * Resource reload/logout safety. Explicitly closes every GPU buffer and resets Palladium
     * session caches. GL deletion is always moved onto the render thread when necessary.
     */
    static void clearAllGpuCaches() {
        Runnable clearTask = () -> {
            synchronized (ACTIVE_RENDERERS) {
                for (HeroStandRenderer renderer : ACTIVE_RENDERERS) {
                    if (renderer.staticArmorCache != null) {
                        renderer.staticArmorCache.clear();
                    }

                    renderer.palladium.resetSessionCache();
                    renderer.palladiumSuitStand.reset();
                    renderer.preparedPalladiumContext = null;
                    renderer.gpuRouter.resetFailures();
                    renderer.occlusionCache.clear();
                    renderer.occlusionLevel = null;
                }
            }
        };

        if (RenderSystem.isOnRenderThread()) {
            clearTask.run();
        } else {
            RenderSystem.recordRenderCall(clearTask::run);
        }
    }

    @Override
    public void render(HeroStandBlockEntity stand, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = stand.getLevel();
        if (level == null || Minecraft.getInstance().player == null || !hasArmor(stand)) return;

        boolean palladiumArmor = palladium.canUseFastPath(stand) && palladiumSuitStand.isAvailable();
        ArmorStand palladiumContext = palladiumArmor
                ? preparePalladiumRenderContext(stand, level)
                : null;

        float rotation = stand.getBlockState().getValue(HeroStandBlock.FACING).toYRot();

        poseStack.pushPose();
        try {
            poseStack.translate(0.5D, 0.125D, 0.5D);
            poseStack.mulPose(Axis.YP.rotationDegrees(-rotation));

            boolean rendered = false;

            if (palladiumContext != null) {
                resetParentModel();

                for (GpuRenderRouter.Backend backend : gpuRouter.orderedBackends()) {
                    if (!gpuRouter.isHealthy(backend)) continue;

                    try {
                        if (!gpuRouter.isSupported(backend)) continue;

                        boolean success = switch (backend) {
                            case MODERN_STATIC -> renderModernStaticPath(
                                    palladiumContext, partialTick, poseStack, buffers, packedLight
                            );
                            case BALANCED_STREAM -> renderBalancedStreamPath(
                                    palladiumContext, partialTick, poseStack, buffers, packedLight
                            );
                            case GENERIC_NATIVE -> renderNativePalladiumPath(
                                    palladiumContext, partialTick, poseStack, buffers, packedLight
                            );
                        };

                        if (success) {
                            gpuRouter.recordSuccess(backend);
                            rendered = true;
                            break;
                        }
                    } catch (Throwable failure) {
                        gpuRouter.recordSoftFailure(backend, failure);
                    }
                }
            }

            if (!rendered) {
                renderVanillaFallback(
                        prepareFallbackRenderContext(stand, level),
                        partialTick,
                        poseStack,
                        buffers,
                        packedLight
                );
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Modern RTX 30+/RX 6000+ path. The base armor model is emitted only when the cache entry is
     * built, then kept resident in STATIC OpenGL vertex buffers. Palladium pack layers remain on
     * the normal live path so animated/custom effects are not frozen.
     */
    private boolean renderModernStaticPath(ArmorStand renderContext, float partialTick,
                                           PoseStack poseStack, MultiBufferSource buffers,
                                           int packedLight) {
        StaticArmorVboCache cache = ensureStaticArmorCache();

        if (!cache.renderOrBuild(
                this,
                palladium,
                renderContext,
                poseStack,
                packedLight,
                partialTick
        )) {
            // A cache miss may intentionally decline this frame when the upload budget is full.
            // Returning false lets BALANCED_STREAM draw the stand normally without treating that
            // as a renderer failure.
            return false;
        }

        poseStack.pushPose();
        try {
            applyManualPalladiumTransform(poseStack);

            if (!palladium.renderPackLayers(
                    renderContext,
                    parentModel,
                    poseStack,
                    buffers,
                    packedLight,
                    partialTick,
                    false
            )) {
                throw new IllegalStateException("Live Palladium pack-layer path failed");
            }

            return true;
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Streaming compatibility/performance balance. This uses Palladium's normal armor hooks and
     * normal live pack-layer rendering without GPU mesh caching.
     */
    private boolean renderBalancedStreamPath(ArmorStand renderContext, float partialTick,
                                             PoseStack poseStack, MultiBufferSource buffers,
                                             int packedLight) {
        poseStack.pushPose();
        try {
            applyManualPalladiumTransform(poseStack);

            armorLayer.render(
                    poseStack, buffers, packedLight, renderContext,
                    0.0F, 0.0F, partialTick, 0.0F, 0.0F, 0.0F
            );

            if (!palladium.renderPackLayers(
                    renderContext,
                    parentModel,
                    poseStack,
                    buffers,
                    packedLight,
                    partialTick,
                    false
            )) {
                throw new IllegalStateException("Balanced Palladium pack-layer path failed");
            }

            return true;
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Final Palladium safety backend. Because renderContext is an actual Palladium SuitStand,
     * EntityRenderDispatcher selects Palladium's real SuitStandRenderer. This preserves custom
     * model assumptions that HeroStand's manual fast paths may not understand.
     */
    private boolean renderNativePalladiumPath(ArmorStand renderContext, float partialTick,
                                              PoseStack poseStack, MultiBufferSource buffers,
                                              int packedLight) {
        Minecraft.getInstance().getEntityRenderDispatcher().render(
                renderContext,
                0.0D, 0.0D, 0.0D,
                0.0F,
                partialTick,
                poseStack,
                buffers,
                packedLight
        );
        return true;
    }

    static void applyManualPalladiumTransform(PoseStack poseStack) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.scale(PALLADIUM_SUIT_SCALE, PALLADIUM_SUIT_SCALE, PALLADIUM_SUIT_SCALE);
        poseStack.translate(0.0D, PALLADIUM_SUIT_Y_OFFSET, 0.0D);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0D, -1.501D, 0.0D);
    }

    private void renderVanillaFallback(ArmorStand renderContext, float partialTick,
                                       PoseStack poseStack, MultiBufferSource buffers,
                                       int packedLight) {
        Minecraft.getInstance().getEntityRenderDispatcher().render(
                renderContext,
                0.0D, 0.0D, 0.0D,
                0.0F,
                partialTick,
                poseStack,
                buffers,
                packedLight
        );
    }

    HumanoidModel<ArmorStand> parentModelForCache() {
        return parentModel;
    }

    ArmorStandArmorModel innerArmorModelForCache() {
        return innerArmorModel;
    }

    ArmorStandArmorModel outerArmorModelForCache() {
        return outerArmorModel;
    }

    private StaticArmorVboCache ensureStaticArmorCache() {
        if (staticArmorCache == null) {
            staticArmorCache = new StaticArmorVboCache(
                    gpuRouter.modernMeshLimit(),
                    gpuRouter.modernBuildsPerTick()
            );
        }
        return staticArmorCache;
    }

    private ArmorStand preparePalladiumRenderContext(HeroStandBlockEntity stand, Level level) {
        ArmorStand context = palladiumSuitStand.getOrCreate(level);
        if (context == null) return null;

        if (preparedPalladiumContext != context) {
            preparedPalladiumContext = context;
            palladium.resetSessionCache();
            gpuRouter.resetFailures();
        }

        copyEquipment(stand, context);
        pinRotationState(context);
        return context;
    }

    private ArmorStand prepareFallbackRenderContext(HeroStandBlockEntity stand, Level level) {
        if (fallbackRenderContext == null || fallbackRenderContext.level() != level) {
            fallbackRenderContext = createBaseContext(level);
        }

        copyEquipment(stand, fallbackRenderContext);
        pinRotationState(fallbackRenderContext);
        return fallbackRenderContext;
    }

    private static ArmorStand createBaseContext(Level level) {
        ArmorStand context = new ArmorStand(level, 0.0D, 0.0D, 0.0D);
        context.setInvisible(true);
        context.setNoBasePlate(true);
        context.setShowArms(true);
        return context;
    }

    private static void copyEquipment(HeroStandBlockEntity stand, ArmorStand context) {
        context.setItemSlot(EquipmentSlot.HEAD, stand.getArmor(HeroStandBlockEntity.HEAD));
        context.setItemSlot(EquipmentSlot.CHEST, stand.getArmor(HeroStandBlockEntity.CHEST));
        context.setItemSlot(EquipmentSlot.LEGS, stand.getArmor(HeroStandBlockEntity.LEGS));
        context.setItemSlot(EquipmentSlot.FEET, stand.getArmor(HeroStandBlockEntity.FEET));
    }

    private static void pinRotationState(ArmorStand context) {
        context.setYRot(0.0F);
        context.yRotO = 0.0F;
        context.setYHeadRot(0.0F);
        context.yHeadRotO = 0.0F;
        context.yBodyRot = 0.0F;
        context.yBodyRotO = 0.0F;
        context.setXRot(0.0F);
        context.xRotO = 0.0F;
    }

    /**
     * Mirror Palladium SuitStandBasePlateModel's neutral humanoid pivots explicitly. Avoid running
     * HumanoidModel.setupAnim here because that method adds normal living-entity idle/limb state
     * that a static SuitStand does not use.
     */
    private void resetParentModel() {
        parentModel.head.x = 0.0F;
        parentModel.head.y = 0.0F;
        parentModel.head.z = 0.0F;
        parentModel.head.xRot = 0.0F;
        parentModel.head.yRot = 0.0F;
        parentModel.head.zRot = 0.0F;

        parentModel.hat.copyFrom(parentModel.head);
        parentModel.hat.visible = false;

        parentModel.body.x = 0.0F;
        parentModel.body.y = 0.0F;
        parentModel.body.z = 0.0F;
        parentModel.body.xRot = 0.0F;
        parentModel.body.yRot = 0.0F;
        parentModel.body.zRot = 0.0F;

        parentModel.rightArm.x = -5.0F;
        parentModel.rightArm.y = 2.0F;
        parentModel.rightArm.z = 0.0F;
        parentModel.rightArm.xRot = 0.0F;
        parentModel.rightArm.yRot = 0.0F;
        parentModel.rightArm.zRot = 0.0F;

        parentModel.leftArm.x = 5.0F;
        parentModel.leftArm.y = 2.0F;
        parentModel.leftArm.z = 0.0F;
        parentModel.leftArm.xRot = 0.0F;
        parentModel.leftArm.yRot = 0.0F;
        parentModel.leftArm.zRot = 0.0F;

        parentModel.rightLeg.x = -1.9F;
        parentModel.rightLeg.y = 12.0F;
        parentModel.rightLeg.z = 0.0F;
        parentModel.rightLeg.xRot = 0.0F;
        parentModel.rightLeg.yRot = 0.0F;
        parentModel.rightLeg.zRot = 0.0F;

        parentModel.leftLeg.x = 1.9F;
        parentModel.leftLeg.y = 12.0F;
        parentModel.leftLeg.z = 0.0F;
        parentModel.leftLeg.xRot = 0.0F;
        parentModel.leftLeg.yRot = 0.0F;
        parentModel.leftLeg.zRot = 0.0F;

        parentModel.setAllVisible(true);
        parentModel.hat.visible = false;
    }

    @Override
    public boolean shouldRender(HeroStandBlockEntity stand, Vec3 cameraPos) {
        if (!hasArmor(stand)) return false;

        int viewDistance = effectiveRenderDistance();
        double viewDistanceSqr = (double) viewDistance * viewDistance;

        BlockPos pos = stand.getBlockPos();
        double dx = cameraPos.x - (pos.getX() + 0.5D);
        double dy = cameraPos.y - (pos.getY() + 0.5D);
        double dz = cameraPos.z - (pos.getZ() + 0.5D);
        if (dx * dx + dy * dy + dz * dz > viewDistanceSqr) return false;

        Level level = stand.getLevel();
        if (level == null || Minecraft.getInstance().player == null) return false;

        if (level != occlusionLevel) {
            occlusionLevel = level;
            occlusionCache.clear();

            if (staticArmorCache != null) {
                staticArmorCache.clear();
            }

            palladiumSuitStand.reset();
            preparedPalladiumContext = null;
        }

        long gameTime = level.getGameTime();
        long key = pos.asLong();
        OcclusionEntry cached = occlusionCache.get(key);

        if (cached != null) {
            long age = gameTime - cached.gameTime;
            double cameraDx = cameraPos.x - cached.cameraX;
            double cameraDy = cameraPos.y - cached.cameraY;
            double cameraDz = cameraPos.z - cached.cameraZ;
            boolean cameraMovedEnough =
                    cameraDx * cameraDx + cameraDy * cameraDy + cameraDz * cameraDz
                            >= CAMERA_MOVE_REFRESH_SQR;

            if (!cameraMovedEnough && gameTime < cached.nextRefreshTick) {
                return cached.visible;
            }

            if (cameraMovedEnough && age < MOVING_REFRESH_TICKS) {
                return cached.visible;
            }
        }

        boolean visible = isSuitVisible(level, stand, cameraPos);
        long nextRefreshTick = gameTime + refreshDelay(key, visible);
        occlusionCache.put(
                key,
                new OcclusionEntry(
                        gameTime,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        visible,
                        nextRefreshTick
                )
        );

        if (occlusionCache.size() > 2048) occlusionCache.clear();
        return visible;
    }

    private boolean isSuitVisible(Level level, HeroStandBlockEntity stand, Vec3 cameraPos) {
        BlockPos pos = stand.getBlockPos();
        for (Vec3 sample : VISIBILITY_SAMPLES) {
            Vec3 target = new Vec3(
                    pos.getX() + sample.x,
                    pos.getY() + sample.y,
                    pos.getZ() + sample.z
            );
            if (hasClearLine(level, stand, cameraPos, target)) return true;
        }
        return false;
    }

    private boolean hasClearLine(Level level, HeroStandBlockEntity stand, Vec3 from, Vec3 to) {
        Vec3 start = from;
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        if (length < 1.0E-4D) return true;
        direction = direction.scale(1.0D / length);

        for (int pass = 0; pass < 8; pass++) {
            BlockHitResult hit = level.clip(new ClipContext(
                    start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    Minecraft.getInstance().player
            ));

            if (hit.getType() == HitResult.Type.MISS) return true;
            if (hit.getBlockPos().equals(stand.getBlockPos())) return true;

            BlockState hitState = level.getBlockState(hit.getBlockPos());
            if (hitState.canOcclude()) return false;

            Vec3 next = hit.getLocation().add(direction.scale(0.05D));
            if (next.distanceToSqr(to) < 0.01D) return true;
            if (next.distanceToSqr(start) < 1.0E-6D) {
                next = start.add(direction.scale(0.05D));
            }
            start = next;
        }

        return true;
    }

    private static long refreshDelay(long key, boolean visible) {
        long mixed = key;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        long nonNegative = mixed & Long.MAX_VALUE;

        long base = visible ? VISIBLE_REFRESH_BASE_TICKS : OCCLUDED_REFRESH_BASE_TICKS;
        long spread = visible ? VISIBLE_REFRESH_SPREAD_TICKS : OCCLUDED_REFRESH_SPREAD_TICKS;
        return base + (nonNegative % spread);
    }

    private static int effectiveRenderDistance() {
        return Math.min(
                HeroStandClientConfig.renderDistance(),
                HeroStandServerConfig.maxSuitRenderDistance()
        );
    }

    private static boolean hasArmor(HeroStandBlockEntity stand) {
        for (int i = 0; i < HeroStandBlockEntity.SLOT_COUNT; i++) {
            if (!stand.isEmpty(i)) return true;
        }
        return false;
    }

    @Override
    public int getViewDistance() {
        return effectiveRenderDistance();
    }

    private record OcclusionEntry(
            long gameTime,
            double cameraX,
            double cameraY,
            double cameraZ,
            boolean visible,
            long nextRefreshTick
    ) {}
}
