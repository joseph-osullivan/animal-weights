package io.github.josephosullivan.animalweights.mixin;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeightsConfig;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side body tint for sick mobs.
 *
 * <p>NeoForge 26.1 removed the event-only path that would have let us hook
 * {@code getModelTint} via {@code RegisterRenderStateModifiersEvent} +
 * {@code ContextKey} (research-task-4-blocker.md), so we land the same effect
 * via two Mixin injections on {@link LivingEntityRenderer}:
 *
 * <ol>
 *   <li>{@code extractRenderState} TAIL — compute the sick predicate from the
 *       authoritative {@link LivingEntity} and stash it on the per-frame
 *       {@link LivingEntityRenderState} via the {@link SickState} duck-type
 *       interface mixed in by {@link LivingEntityRenderStateMixin}.</li>
 *   <li>{@code getModelTint} HEAD ({@code cancellable=true}) — if the render
 *       state was flagged sick AND the client config has
 *       {@code enable_sick_tint = true}, return the configured tint and
 *       short-circuit vanilla. Other tint sources (e.g. red damage flash) are
 *       applied on top of the model later in the pipeline; we only override
 *       the per-mob base tint here.</li>
 * </ol>
 *
 * <p>The sick predicate consults the
 * {@link io.github.josephosullivan.animalweights.AnimalWeightsTags#TRACKED}
 * entity-type tag via {@link AnimalWeightAttachment#isTracked} — same source
 * of truth as the server handlers, so the tint follows whatever the tag
 * resolves to at runtime (default vanilla set, or modpack/datapack
 * extensions). Babies are excluded because they don't participate in the
 * sick state at all. Weight is read through {@link AnimalWeightAttachment#get},
 * which works client-side because the attachment is synced on the entity.
 *
 * <p>Run-006: both the tint colour and the master enable toggle are read
 * from {@link AnimalWeightsConfig.Client}. The tint defaults to
 * {@code 0xFF814F30} (desaturated brown) and the toggle defaults to
 * {@code true}, preserving v0.2.0 behaviour for users who never edit the
 * config.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void animalweights$captureSick(LivingEntity entity,
                                           LivingEntityRenderState state,
                                           float partialTicks,
                                           CallbackInfo ci) {
        boolean sick = AnimalWeightAttachment.isTracked(entity)
                && !(entity instanceof Mob mob && mob.isBaby())
                && AnimalWeightAttachment.get(entity) == 0;
        ((SickState) state).animalweights$setSick(sick);
    }

    @Inject(method = "getModelTint", at = @At("HEAD"), cancellable = true)
    private void animalweights$applySickTint(LivingEntityRenderState state,
                                             CallbackInfoReturnable<Integer> cir) {
        if (!((SickState) state).animalweights$isSick()) {
            return;
        }
        if (!AnimalWeightsConfig.CLIENT.enableSickTint.get()) {
            return; // user-disabled — fall through to the vanilla default tint
        }
        cir.setReturnValue(AnimalWeightsConfig.CLIENT.sickTintArgb.get());
    }
}
