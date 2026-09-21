package com.herostand.mixin;

import com.herostand.client.PalladiumConditionContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.threetag.palladium.condition.Condition;
import net.threetag.palladium.util.context.DataContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Satsu 3.5.3 is overwhelmingly Gecko render layers. Palladium's
 * GeckoRenderLayer.render() calls IPackRenderLayer.conditionsFulfilled(), whose stock
 * implementation creates a new DataContext/HashMap for every individual condition.
 *
 * Redirect only that concrete Gecko call site. This avoids the unsupported/static-interface
 * injector path and leaves Palladium's public interface untouched.
 */
@Pseudo
@Mixin(
        targets = "net.threetag.palladium.compat.geckolib.renderlayer.GeckoRenderLayer",
        remap = false
)
public abstract class PalladiumConditionMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/threetag/palladium/client/renderer/renderlayer/IPackRenderLayer;conditionsFulfilled(Lnet/minecraft/world/entity/Entity;Ljava/util/List;Ljava/util/List;)Z"
            ),
            require = 0,
            remap = false
    )
    private boolean herostand$reuseGeckoConditionContext(
            Entity entity,
            List<Condition> bothConditions,
            List<Condition> thirdPersonConditions) {
        if (!(entity instanceof LivingEntity living)) {
            return true;
        }

        DataContext context;
        Object active = PalladiumConditionContext.current();

        if (active instanceof DataContext reusable
                && reusable.getEntity() == living) {
            context = reusable;
            PalladiumConditionContext.noteReuse(
                    bothConditions.size() + thirdPersonConditions.size());
        } else {
            context = DataContext.forEntity(living);
            PalladiumConditionContext.noteFallbackBuild();
        }

        for (Condition condition : bothConditions) {
            if (!condition.active(context)) {
                return false;
            }
        }

        for (Condition condition : thirdPersonConditions) {
            if (!condition.active(context)) {
                return false;
            }
        }

        return true;
    }
}
