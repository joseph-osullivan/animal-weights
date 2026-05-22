package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.ai.WanderToHabitatGoal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Installs the {@link WanderToHabitatGoal} on every target-species
 * {@link Animal} as it joins a {@link ServerLevel}.
 *
 * <p>Target species is the
 * {@link io.github.josephosullivan.animalweights.AnimalWeightsTags#TRACKED}
 * tag — by default cow, mooshroom, pig, sheep, chicken, rabbit; extensible
 * to modded animals via datapack. Babies are
 * skipped — they grow into adults, and the {@code BabyEntitySpawnEvent}
 * path produces a fresh entity which will re-fire
 * {@link EntityJoinLevelEvent} on adulthood-side spawn anyway. Adult
 * entities loaded from disk also re-fire this event on chunk load, so we
 * pick them up too.
 *
 * <p><b>Priority</b>: 7 — below vanilla flee / breed / tempt / follow-parent
 * (typically 1-4) so existing reactive behaviour still wins, but above
 * {@code RandomLookAroundGoal} (priority 7 on cows, 8 on some species).
 * Sits adjacent to vanilla's own {@code WaterAvoidingRandomStrollGoal}
 * (priority 5 on cows); our habitat-seeking goal does not replace
 * vanilla stroll — both can be active, and whichever the goal selector
 * picks based on tick wins.
 *
 * <p>Server-side only: client {@code ServerLevel} instances never exist
 * (the integrated server runs server-side inside the client JVM but is
 * still {@code ServerLevel}). We gate on {@code ServerLevel} explicitly
 * because client-side {@code ClientLevel} animal join events fire too,
 * and adding goals to client-side proxies would be wasted work.
 *
 * <p>Self-registers via {@link EventBusSubscriber} — no manual wiring in
 * {@code AnimalWeights} required. No {@code Dist} restriction because the
 * integrated server in single-player runs on {@code Dist.CLIENT} (see
 * MC_API_LANDMINES note on this in {@code DEDICATED_SERVER}); we filter
 * at runtime via {@code instanceof ServerLevel}.
 */
@EventBusSubscriber(modid = AnimalWeights.MOD_ID)
public final class HabitatGoalInstaller {

    /**
     * Goal priority for the installed habitat-seeking goal. See class
     * javadoc for the rationale.
     */
    static final int GOAL_PRIORITY = 7;

    /**
     * Speed modifier passed to {@link WanderToHabitatGoal}. 1.0 matches the
     * vanilla {@code WaterAvoidingRandomStrollGoal} speed for cows / pigs;
     * we are walking, not panicking.
     */
    static final double SPEED_MODIFIER = 1.0;

    private HabitatGoalInstaller() {
        // event handler — no instances
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return; // client-side proxies don't run AI; no need to install
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Animal animal)) {
            return;
        }
        if (!AnimalWeightAttachment.isTracked(animal)) {
            return;
        }
        if (animal.isBaby()) {
            return; // babies don't carry the weight gameplay; grow-up will re-fire join
        }
        animal.goalSelector.addGoal(GOAL_PRIORITY, new WanderToHabitatGoal(animal, SPEED_MODIFIER));
    }
}
