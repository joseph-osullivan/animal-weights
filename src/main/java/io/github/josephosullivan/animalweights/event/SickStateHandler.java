package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Implements the "sick" state for target-species mobs (membership defined by
 * the {@link io.github.josephosullivan.animalweights.AnimalWeightsTags#TRACKED}
 * entity-type tag — by default cow, mooshroom, pig, sheep, chicken, rabbit;
 * extensible to modded animals via datapack) whose {@link AnimalWeightAttachment
 * weight} has fallen to {@link AnimalWeightsTuning#WEIGHT_MIN 0}.
 *
 * <p>Three visible effects:
 * <ul>
 *   <li><b>Slowness</b>: each server tick a weight-0 target species mob is
 *       given {@link MobEffects#SLOWNESS} (amplifier 0, 40 ticks,
 *       hidden particles, no HUD icon). We only re-apply when the effect is
 *       missing or about to expire (&lt; {@link #SICK_EFFECT_REFRESH_THRESHOLD_TICKS}
 *       ticks remaining) so we are not paying for a {@code setData} call on
 *       every single entity tick.</li>
 *   <li><b>Weakness</b>: same cadence as Slowness but {@link MobEffects#WEAKNESS}
 *       with a visible HUD icon (per run-005 decisions.md Q4) so the player
 *       targeting the animal sees a clear sick tell. Hidden particles, same
 *       duration and refresh threshold as Slowness.</li>
 *   <li><b>Breeding block</b>: {@link BabyEntitySpawnEvent} is cancelled when
 *       either parent is a weight-0 target species. We also call
 *       {@link Animal#resetLove()} on both parents to be defensive — vanilla
 *       {@code Animal#spawnChildFromBreeding} already resets love on cancel,
 *       but other mods can intercept and we want to leave parents in a clean
 *       state regardless.</li>
 * </ul>
 *
 * <p>Self-registers via {@link EventBusSubscriber} on the dedicated-server
 * dist; the sick state is server-authoritative and the client never needs to
 * run it (the effect is replicated to clients through normal vanilla effect
 * sync).
 */
@EventBusSubscriber(modid = AnimalWeights.MOD_ID)
public final class SickStateHandler {

    /**
     * Duration applied each refresh for both Slowness and Weakness, in ticks
     * (2 seconds at 20 TPS). Renamed from {@code SLOWNESS_DURATION_TICKS} in
     * run-005 task-7 when Weakness joined Slowness as a sick effect.
     */
    static final int SICK_EFFECT_DURATION_TICKS = 40;

    /** Amplifier for both effects. 0 == level I. */
    static final int SICK_EFFECT_AMPLIFIER = 0;

    /**
     * If the existing sick effect (either Slowness or Weakness) has fewer than
     * this many ticks left, we top it up. Picked at 10 ticks (0.5 s) so we get
     * at least one full refresh before a mob ever drops below an effect — but
     * we still avoid re-{@code addEffect}ing every single tick.
     */
    static final int SICK_EFFECT_REFRESH_THRESHOLD_TICKS = 10;

    private SickStateHandler() {
        // event handler — no instances
    }

    /**
     * Pure-int predicate: a mob is "sick" iff its weight is exactly 0.
     *
     * <p>Negative inputs are also treated as sick-free here (return {@code false})
     * because the contract of {@link AnimalWeightAttachment#set(LivingEntity, int)}
     * is that no negative value can ever reach storage — but if a future caller
     * bypasses the helper we'd rather degrade to "not sick" than re-trigger the
     * slowness effect indefinitely.
     */
    public static boolean isSick(int weight) {
        return weight == 0;
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        // Perf fix #6: throttle to roughly every 8th tick using an entity-id
        // offset so per-entity work is scattered across ticks instead of all
        // firing on the same one. The sick effects use a 40-tick duration
        // with a 10-tick refresh threshold (SICK_EFFECT_REFRESH_THRESHOLD_TICKS),
        // so checking every ~8 ticks still keeps us comfortably ahead of
        // expiry. We need a ServerLevel for getGameTime() and to keep this
        // server-side anyway.
        if (!(entity.level() instanceof ServerLevel sl)) {
            return;
        }
        if (((sl.getGameTime() + entity.getId()) & 7) != 0) {
            return;
        }
        if (!AnimalWeightAttachment.isTracked(entity)) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return; // tag could legally contain non-LivingEntity types; gate before cast
        }
        if (living instanceof Mob mobEntity && mobEntity.isBaby()) {
            return; // babies don't participate in sick state
        }
        if (!isSick(AnimalWeightAttachment.get(living))) {
            return;
        }

        MobEffectInstance existingSlowness = living.getEffect(MobEffects.SLOWNESS);
        if (existingSlowness == null
                || existingSlowness.getDuration() < SICK_EFFECT_REFRESH_THRESHOLD_TICKS) {
            living.addEffect(new MobEffectInstance(
                    MobEffects.SLOWNESS,
                    SICK_EFFECT_DURATION_TICKS,
                    SICK_EFFECT_AMPLIFIER,
                    /* ambient */ false,
                    /* visible */ false));
        }

        // Weakness as second sick effect (run-005 task-7, decisions.md Q4).
        // visible=true so the player gets a HUD icon when targeting the animal;
        // ambient=false to match Slowness. Same duration/refresh threshold.
        MobEffectInstance existingWeakness = living.getEffect(MobEffects.WEAKNESS);
        if (existingWeakness == null
                || existingWeakness.getDuration() < SICK_EFFECT_REFRESH_THRESHOLD_TICKS) {
            living.addEffect(new MobEffectInstance(
                    MobEffects.WEAKNESS,
                    SICK_EFFECT_DURATION_TICKS,
                    SICK_EFFECT_AMPLIFIER,
                    /* ambient */ false,
                    /* visible */ true));
        }
    }

    @SubscribeEvent
    public static void onBabyEntitySpawn(BabyEntitySpawnEvent event) {
        Mob parentA = event.getParentA();
        Mob parentB = event.getParentB();
        if (!hasSickTargetParent(parentA) && !hasSickTargetParent(parentB)) {
            return;
        }

        event.setCanceled(true);
        resetLoveSafely(parentA);
        resetLoveSafely(parentB);
    }

    /** True iff this parent is a target species AND its current weight is 0. */
    private static boolean hasSickTargetParent(Mob parent) {
        if (!AnimalWeightAttachment.isTracked(parent)) {
            return false;
        }
        return isSick(AnimalWeightAttachment.get(parent));
    }

    /**
     * Reset a parent's {@code inLove} state, preferring the public
     * {@link Animal#resetLove()} API. If the parent isn't an {@link Animal}
     * subclass for some reason (shouldn't happen for Cow/Pig/Sheep, but we
     * still handle the {@code event.getParentB() != Animal} edge case from the
     * spec) we no-op rather than crash.
     */
    private static void resetLoveSafely(Mob parent) {
        if (parent instanceof Animal animal) {
            animal.resetLove();
        }
    }
}
