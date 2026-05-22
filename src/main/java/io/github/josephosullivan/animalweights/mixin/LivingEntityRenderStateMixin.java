package io.github.josephosullivan.animalweights.mixin;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixes a {@code boolean sick} field into {@link LivingEntityRenderState} and
 * exposes it via the {@link SickState} duck-type interface, so
 * {@code LivingEntityRendererMixin} can stash a per-frame flag during
 * {@code extractRenderState} and read it back in {@code getModelTint}.
 *
 * <p>The field is {@link Unique} so Mixin renames it under the hood and we
 * can't collide with a same-named vanilla field if Mojang adds one. The
 * {@code animalweights$} method-name prefix is the standard Mixin convention
 * for injected accessors.
 */
@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements SickState {

    @Unique
    private boolean animalweights$sick;

    @Override
    public boolean animalweights$isSick() {
        return this.animalweights$sick;
    }

    @Override
    public void animalweights$setSick(boolean sick) {
        this.animalweights$sick = sick;
    }
}
