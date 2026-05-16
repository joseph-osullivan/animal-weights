package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Implements the "sick" state for target-species mobs (Cow/Pig/Sheep, plus
 * Chicken/Rabbit/Mooshroom in run-003) whose
 * {@link AnimalWeightAttachment weight} has fallen to {@link AnimalWeightsTuning#WEIGHT_MIN 0}.
 * Mooshroom is auto-covered via {@link AbstractCow} — in MC 26.1 it sits
 * alongside {@link net.minecraft.world.entity.animal.cow.Cow} under
 * {@code AbstractCow}, not as a subclass of {@code Cow}.
 *
 * <p>Two visible effects:
 * <ul>
 *   <li><b>Slowness</b>: each server tick a weight-0 target species mob is
 *       given {@link MobEffects#SLOWNESS} (amplifier 0, 40 ticks,
 *       hidden particles/icon). We only re-apply when the effect is missing
 *       or about to expire (&lt; {@link #SLOWNESS_REFRESH_THRESHOLD_TICKS} ticks
 *       remaining) so we are not paying for a {@code setData} call on every
 *       single entity tick.</li>
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

    /** Slowness duration applied each refresh, in ticks (2 seconds at 20 TPS). */
    static final int SLOWNESS_DURATION_TICKS = 40;

    /** Slowness amplifier. 0 == "Slowness I". */
    static final int SLOWNESS_AMPLIFIER = 0;

    /**
     * If the existing slowness effect has fewer than this many ticks left, we
     * top it up. Picked at 10 ticks (0.5 s) so we get at least one full refresh
     * before a mob ever drops below the effect — but we still avoid
     * re-{@code addEffect}ing every single tick.
     */
    static final int SLOWNESS_REFRESH_THRESHOLD_TICKS = 10;

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
        if (entity.level().isClientSide()) {
            return;
        }
        if (!isTargetSpecies(entity)) {
            return;
        }
        LivingEntity living = (LivingEntity) entity;
        if (living instanceof Mob mobEntity && mobEntity.isBaby()) {
            return; // babies don't participate in sick state
        }
        if (!isSick(AnimalWeightAttachment.get(living))) {
            return;
        }

        MobEffectInstance existing = living.getEffect(MobEffects.SLOWNESS);
        if (existing == null || existing.getDuration() < SLOWNESS_REFRESH_THRESHOLD_TICKS) {
            living.addEffect(new MobEffectInstance(
                    MobEffects.SLOWNESS,
                    SLOWNESS_DURATION_TICKS,
                    SLOWNESS_AMPLIFIER,
                    /* ambient */ false,
                    /* visible */ false));
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

    /**
     * True iff the entity is a target species: Cow/Mooshroom (any
     * {@link AbstractCow}), Pig, Sheep, Chicken, or Rabbit. Other species are
     * out of scope.
     */
    private static boolean isTargetSpecies(Entity entity) {
        return entity instanceof AbstractCow || entity instanceof Pig
                || entity instanceof Sheep || entity instanceof Chicken
                || entity instanceof Rabbit;
    }

    /** True iff this parent is a target species AND its current weight is 0. */
    private static boolean hasSickTargetParent(Mob parent) {
        if (!isTargetSpecies(parent)) {
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
