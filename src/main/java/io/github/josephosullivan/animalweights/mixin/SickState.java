package io.github.josephosullivan.animalweights.mixin;

/**
 * Duck-type interface mixed into {@code net.minecraft.client.renderer.entity.state.LivingEntityRenderState}
 * by {@link LivingEntityRenderStateMixin}.
 *
 * <p>The Minecraft render state class is rebuilt every frame from authoritative
 * entity data inside {@code LivingEntityRenderer#extractRenderState}. We need
 * to smuggle a single boolean — "is this mob sick?" — from {@code extractRenderState}
 * through to {@code getModelTint}, which only receives the state object. The
 * Mixin {@code @Unique} field on the state class is the cleanest way to do
 * that without dragging in a separate map keyed on entity id.
 *
 * <p>Method names use the {@code animalweights$} prefix per Mixin convention to
 * avoid colliding with vanilla method names if Mojang ever adds a same-named
 * field in a future patch.
 */
public interface SickState {

    /** True iff this render state was extracted from a sick mob this frame. */
    boolean animalweights$isSick();

    /**
     * Set the sick flag for this render state. Called by {@code LivingEntityRendererMixin}
     * at the tail of {@code extractRenderState}.
     */
    void animalweights$setSick(boolean sick);
}
