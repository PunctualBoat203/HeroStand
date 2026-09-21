package com.herostand.mixin;

import com.herostand.client.PalladiumConditionContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.threetag.palladium.condition.Condition;
import net.threetag.palladium.util.context.DataContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Palladium 4.5.9 allocates a fresh DataContext/HashMap for every condition in
 * IPackRenderLayer.conditionsFulfilled(). Satsu suits can contain dozens of nested Gecko layers,
 * so a wall of static displays turns that into hundreds of MB/s of avoidable garbage.
 *
 * HeroStand supplies one reusable entity-only DataContext while its fake SuitStand is rendering.
 * Outside HeroStand, this preserves Palladium semantics but builds one context per helper call
 * instead of one context per individual condition.
 */
@Pseudo
@Mixin(
        targets = "net.threetag.palladium.client.renderer.renderlayer.IPackRenderLayer",
        remap = false
)
public abstract class PalladiumConditionMixin {

    @Inject(
            method = "conditionsFulfilled(Lnet/minecraft/world/entity/Entity;Ljava/util/List;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void herostand$reuseSingleConditionContext(
            Entity entity,
            List<Condition> conditions,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof LivingEntity living)) {
            cir.setReturnValue(true);
            return;
        }

        DataContext context = herostand$contextFor(living);

        for (Condition condition : conditions) {
            if (!condition.active(context)) {
                cir.setReturnValue(false);
                return;
            }
        }

        cir.setReturnValue(true);
    }

    @Inject(
            method = "conditionsFulfilled(Lnet/minecraft/world/entity/Entity;Ljava/util/List;Ljava/util/List;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void herostand$reuseCombinedConditionContext(
            Entity entity,
            List<Condition> bothConditions,
            List<Condition> specificConditions,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof LivingEntity living)) {
            cir.setReturnValue(true);
            return;
        }

        DataContext context = herostand$contextFor(living);

        for (Condition condition : bothConditions) {
            if (!condition.active(context)) {
                cir.setReturnValue(false);
                return;
            }
        }

        for (Condition condition : specificConditions) {
            if (!condition.active(context)) {
                cir.setReturnValue(false);
                return;
            }
        }

        cir.setReturnValue(true);
    }

    private static DataContext herostand$contextFor(LivingEntity living) {
        Object active = PalladiumConditionContext.current();

        if (active instanceof DataContext context
                && context.getEntity() == living) {
            PalladiumConditionContext.noteReuse(1);
            return context;
        }

        PalladiumConditionContext.noteFallbackBuild();
        return DataContext.forEntity(living);
    }
}
