package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.ai.WanderToHabitatGoal;
import io.github.josephosullivan.animalweights.event.HabitatGoalInstaller;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.cow.Cow;

import java.util.Set;

/**
 * Bodies for Tier-2 GameTests covering the {@link HabitatGoalInstaller} +
 * {@link WanderToHabitatGoal} install path added in run-005 task-6.
 *
 * <p>Spec source: {@code docs/workflow-runs/005-v0.2.0-parity/decisions.md} Q1
 * — "WanderToHabitatGoal — animals seek better habitat. Install via
 * EntityJoinLevelEvent; reuse existing habitat predicates as the score
 * function." Babies are skipped per
 * {@link HabitatGoalInstaller#onEntityJoinLevel}'s {@code isBaby()} guard
 * (decisions.md cross-cutting acceptance + run-004 baby-skip pattern).
 *
 * <p><b>Spawn helper choice (load-bearing):</b> these tests use
 * {@link GameTestHelper#spawn} rather than {@code spawnWithNoFreeWill}.
 * {@code spawnWithNoFreeWill} calls {@code Mob#removeFreeWill()} which calls
 * {@code removeAllGoals(p -> true)} — that strips the freshly-installed habitat
 * goal milliseconds after the join event fires it. We want to verify the
 * INSTALL path, so we need the goal to survive past spawn.
 */
public final class WanderHabitatGoalGameTests {

    private WanderHabitatGoalGameTests() {}

    private static final BlockPos COW_REL = new BlockPos(2, 2, 2);

    /**
     * Spec source: decisions.md Q1 — habitat goal is installed via
     * {@code EntityJoinLevelEvent}. An adult cow spawned into a ServerLevel
     * must have a {@link WanderToHabitatGoal} in its
     * {@code goalSelector.getAvailableGoals()} immediately after spawn.
     *
     * <p>Bug it catches: someone deletes or guards-out the
     * {@link HabitatGoalInstaller} event handler. Without this, the v0.2.0
     * habitat-seeking behaviour silently regresses to vanilla random stroll.
     */
    public static void adultCowHasHabitatGoalInstalledOnSpawn(GameTestHelper helper) {
        // Use spawn() NOT spawnWithNoFreeWill() — the latter strips all goals
        // after install, defeating the test. See class javadoc.
        Cow adult = helper.spawn(EntityType.COW, COW_REL);
        // Sanity guard: spawn must produce an adult by default.
        if (adult.isBaby()) {
            helper.fail("test setup: EntityType.COW spawn produced a baby; vanilla default changed?");
            return;
        }

        Set<WrappedGoal> goals = adult.goalSelector.getAvailableGoals();
        boolean hasHabitatGoal = false;
        for (WrappedGoal wrapped : goals) {
            Goal inner = wrapped.getGoal();
            if (inner instanceof WanderToHabitatGoal) {
                hasHabitatGoal = true;
                break;
            }
        }
        if (!hasHabitatGoal) {
            helper.fail("adult cow has no WanderToHabitatGoal in goalSelector after spawn — "
                    + "HabitatGoalInstaller did not fire on EntityJoinLevelEvent. "
                    + "Goals present: " + goalSummary(goals));
            return;
        }
        helper.succeed();
    }

    /**
     * Spec source: decisions.md cross-cutting acceptance + run-004 baby-skip
     * pattern — babies don't carry the weight mechanic, so they don't need
     * the habitat goal either. {@link HabitatGoalInstaller#onEntityJoinLevel}
     * has an {@code isBaby()} guard; the grow-up path re-fires
     * {@code EntityJoinLevelEvent} when the entity flips to adult.
     *
     * <p>Bug it catches: someone removes the {@code isBaby()} guard, leaving
     * babies with a stroll goal they shouldn't have (cosmetic, but the
     * symmetry with {@link io.github.josephosullivan.animalweights.event.DailyEvalHandler}'s
     * baby skip is part of the contract).
     *
     * <p>Why this test matters beyond cosmetics: the baby goal would be
     * installed at priority 7, possibly interfering with the parent-following
     * goal during the baby's growth phase. Catching the guard removal here
     * is cheap; debugging "why are babies wandering away from mom" in
     * playtest would be expensive.
     */
    public static void babyCowDoesNotHaveHabitatGoalInstalled(GameTestHelper helper) {
        // CRITICAL ORDERING: setBaby() must happen BEFORE addFreshEntity() so
        // that EntityJoinLevelEvent sees a baby and skips installation. We
        // can't use the helper's spawn() (which fires join immediately on
        // addFreshEntity) and then setBaby() — by that point the goal is
        // already installed. Workaround: spawn fresh adult, then setBaby(),
        // then verify goal is removed by an explicit removeAllGoals path? No
        // — that doesn't actually test the install-time skip.
        //
        // Real workaround: create the entity manually, setBaby(), then add
        // to level. This mirrors the natural baby-spawn path
        // (BabyEntitySpawnEvent → child entity with isBaby()==true → join).
        Cow baby = EntityType.COW.create(helper.getLevel(),
                net.minecraft.world.entity.EntitySpawnReason.LOAD);
        if (baby == null) {
            helper.fail("test setup: EntityType.COW.create returned null");
            return;
        }
        baby.setBaby(true);
        BlockPos abs = helper.absolutePos(COW_REL);
        baby.snapTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0F, 0F);
        baby.setPersistenceRequired();

        if (!helper.getLevel().addFreshEntity(baby)) {
            helper.fail("test setup: addFreshEntity rejected the baby cow");
            return;
        }
        if (!baby.isBaby()) {
            helper.fail("test setup: baby flipped to adult during addFreshEntity — "
                    + "setBaby() must register before EntityJoinLevelEvent fires");
            return;
        }

        Set<WrappedGoal> goals = baby.goalSelector.getAvailableGoals();
        for (WrappedGoal wrapped : goals) {
            Goal inner = wrapped.getGoal();
            if (inner instanceof WanderToHabitatGoal) {
                helper.fail("baby cow has WanderToHabitatGoal installed — "
                        + "HabitatGoalInstaller's isBaby() guard is broken. "
                        + "Goals present: " + goalSummary(goals));
                return;
            }
        }
        helper.succeed();
    }

    /**
     * Renders a goal set into a debug-friendly string of inner-class names.
     * Helps surface "what goals ARE on this entity" when the assertion fails.
     */
    private static String goalSummary(Set<WrappedGoal> goals) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (WrappedGoal wrapped : goals) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(wrapped.getGoal().getClass().getSimpleName());
        }
        sb.append("]");
        return sb.toString();
    }
}
