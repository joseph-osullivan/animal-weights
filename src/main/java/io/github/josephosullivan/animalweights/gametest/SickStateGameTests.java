package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.event.SickStateHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bodies for {@link SickStateHandler} Tier-2 GameTests, asserting the spec
 * from run-002 {@code design.md} "Sick state" section.
 *
 * <p>Two surfaces:
 * <ul>
 *   <li><b>Slowness</b>: weight-0 Cow/Pig/Sheep on the server gets
 *       {@link MobEffects#SLOWNESS} re-applied each entity tick when the
 *       existing effect is missing or about to expire.</li>
 *   <li><b>Breeding block</b>: {@link BabyEntitySpawnEvent} is cancelled if
 *       either parent is a weight-0 target species; both parents' inLove is
 *       reset.</li>
 * </ul>
 *
 * <p>The slowness tests rely on the
 * {@link net.neoforged.neoforge.event.tick.EntityTickEvent.Post} firing on
 * the running server during the test cell's tick loop — we set the weight
 * then defer the assertion 1+ ticks so the handler has a chance to run.
 *
 * <p>The breeding tests construct {@link BabyEntitySpawnEvent} directly and
 * post it on the NeoForge bus; the production handler's
 * {@code onBabyEntitySpawn} listener runs synchronously and we check the
 * event's {@code isCanceled} state.
 */
public final class SickStateGameTests {

    private SickStateGameTests() {}

    private static final BlockPos COW_REL = new BlockPos(2, 2, 2);
    private static final BlockPos COW_PARTNER_REL = new BlockPos(2, 2, 4);

    // ----------------------------------------------------------------------
    // Slowness effect — design.md "applied once" / "refreshed" / "over-broad"
    // ----------------------------------------------------------------------

    /**
     * Spec row: "spawn weight-0 cow, wait 1 tick → has SLOWNESS amp 0".
     * Pins the slowness tick handler runs on EntityTickEvent.Post for a
     * weight-0 target species mob.
     */
    public static void weightZeroCowGetsSlownessAfterOneTick(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        cow.setNoGravity(true);
        AnimalWeightAttachment.set(cow, 0);

        helper.succeedWhen(() -> {
            MobEffectInstance effect = cow.getEffect(MobEffects.SLOWNESS);
            if (effect == null) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 cow has no SLOWNESS effect yet"),
                        0);
            }
            if (effect.getAmplifier() != 0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 cow SLOWNESS amplifier expected 0; got "
                                        + effect.getAmplifier()),
                        0);
            }
        });
    }

    /**
     * Spec row: "spawn weight-0 cow, wait 60 ticks → still has SLOWNESS
     * (refreshed)". Pins the re-application path: the handler must re-apply
     * before the 40-tick duration expires, so the effect persists across
     * any window longer than the duration.
     */
    public static void weightZeroCowKeepsSlownessAfterSixtyTicks(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        cow.setNoGravity(true); // 3x3 empty template has no floor — prevents fall race
        AnimalWeightAttachment.set(cow, 0);

        // 60 ticks > SLOWNESS_DURATION_TICKS (40), so if re-apply didn't
        // happen the effect would have expired by now.
        helper.runAfterDelay(60L, () -> {
            MobEffectInstance effect = cow.getEffect(MobEffects.SLOWNESS);
            if (effect == null) {
                helper.fail("weight-0 cow lost SLOWNESS after 60 ticks — "
                        + "re-application path is broken; effect should refresh every "
                        + "tick when duration < SLOWNESS_REFRESH_THRESHOLD_TICKS");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "spawn weight-1 (healthy) cow, wait long enough for any
     * spuriously-applied SLOWNESS to expire → no SLOWNESS". Pins the
     * over-broad-effect bug: the handler must only target sick mobs.
     *
     * <p>Robustness note: the GameTest server may fire
     * {@link io.github.josephosullivan.animalweights.event.DailyEvalHandler}
     * on the first {@code LevelTickEvent.Post} of the shared test world. If
     * the cow is in an empty/bad cell at that moment, eval drops weight 1 → 0
     * in the same tick that {@link SickStateHandler} runs (level tick fires
     * before entity tick), and slowness gets applied before the test can
     * re-set. To rule that out, we spawn the cow in a *perfect pen* at an
     * isolated world location — eval (if it fires) sees met=4 → +1, so weight
     * goes 1 → 2 and never to 0. No slowness path can possibly trigger.
     */
    public static void weightOneCowHasNoSlowness(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pen = nextIsolatedPos();
        buildPerfectPen(level, pen);

        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        cow.snapTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0F, 0F);
        AnimalWeightAttachment.set(cow, 1);

        // 50 ticks > SLOWNESS_DURATION_TICKS=40, so any spuriously-applied
        // slowness from the spawn-tick race would have expired by now.
        helper.runAfterDelay(50L, () -> {
            int weight = AnimalWeightAttachment.get(cow);
            MobEffectInstance effect = cow.getEffect(MobEffects.SLOWNESS);
            if (effect != null) {
                helper.fail("cow has SLOWNESS effect (weight=" + weight + " at check) — "
                        + "isSick predicate is over-broad (should only trigger on weight 0)");
                return;
            }
            // Weight may have legitimately climbed to 2 if dawn eval ran — that's
            // a valid healthy outcome, not a regression. Anything ≥ 1 is fine.
            if (weight < 1) {
                helper.fail("cow weight unexpectedly dropped below 1 in perfect pen: weight=" + weight);
                return;
            }
            helper.succeed();
        });
    }

    // ----------------------------------------------------------------------
    // Isolation + perfect-pen helpers (used by tests that must survive auto-eval)
    // ----------------------------------------------------------------------

    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);
    private static final int ISOLATION_BASE_X = 800_000;
    private static final int ISOLATION_STRIDE = 1000;
    private static final int ISOLATION_Y = 128;

    private static BlockPos nextIsolatedPos() {
        int n = ISOLATION_X.getAndIncrement();
        return new BlockPos(ISOLATION_BASE_X + n * ISOLATION_STRIDE, ISOLATION_Y, 0);
    }

    /**
     * Build a 3×2 grass platform + adjacent water cauldron + torch overhead
     * around {@code center}. Mirrors {@code DailyEvalGameTests.buildPerfectPen}
     * but lives here so SickStateGameTests doesn't introduce a cross-class
     * dependency. Calibrated to satisfy all four daily-eval conditions
     * (light, water, grazing, stretching with reach=6) when a cow is placed
     * at {@code center}.
     */
    private static void buildPerfectPen(ServerLevel level, BlockPos center) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                level.setBlock(center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            }
        }
        level.setBlock(center.offset(-1, -1, 0), Blocks.WATER_CAULDRON.defaultBlockState(), 3);
        level.setBlock(center.above(), Blocks.TORCH.defaultBlockState(), 3);
    }

    /**
     * Spec row: "weight-0 zombie next to weight-0 cow → zombie unaffected".
     * Pins that the handler's target-species filter rejects Zombie even when
     * its attachment is somehow set to 0. Without this check, any mob with a
     * 0 weight would get permanent slowness.
     *
     * <p>Wait 10 ticks for the {@link SickStateHandler} 8-tick throttle to
     * have fired at least once — otherwise a too-short window could mask an
     * over-broad target-species filter bug.
     */
    public static void weightZeroZombieNotAffectedBySlowness(GameTestHelper helper) {
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, COW_REL);
        AnimalWeightAttachment.set(zombie, 0);

        helper.runAfterDelay(10L, () -> {
            MobEffectInstance effect = zombie.getEffect(MobEffects.SLOWNESS);
            if (effect != null) {
                helper.fail("weight-0 zombie has SLOWNESS effect — "
                        + "isTargetSpecies filter is over-broad");
                return;
            }
            helper.succeed();
        });
    }

    // ----------------------------------------------------------------------
    // Breeding cancellation
    // ----------------------------------------------------------------------

    /**
     * Spec row: "two weight-0 cows fed wheat → no baby; both parents
     * inLove == 0 after". Both-sick parent case: event cancels, both
     * parents' love is reset.
     */
    public static void weightZeroCowPairBreedCancelledAndBothReset(GameTestHelper helper) {
        Cow parentA = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        Cow parentB = helper.spawnWithNoFreeWill(EntityType.COW, COW_PARTNER_REL);
        AnimalWeightAttachment.set(parentA, 0);
        AnimalWeightAttachment.set(parentB, 0);

        // Pre-arm both parents with inLove > 0 so we can verify the reset.
        parentA.setInLoveTime(600);
        parentB.setInLoveTime(600);
        if (parentA.getInLoveTime() <= 0 || parentB.getInLoveTime() <= 0) {
            helper.fail("test setup: setInLoveTime did not register on cow parents");
            return;
        }

        // Construct + fire the breeding event. The handler is on the
        // NeoForge bus.
        BabyEntitySpawnEvent event = new BabyEntitySpawnEvent(parentA, parentB, /* child */ null);
        NeoForge.EVENT_BUS.post(event);

        if (!event.isCanceled()) {
            helper.fail("two weight-0 cows: BabyEntitySpawnEvent expected cancelled; was not");
            return;
        }
        if (parentA.getInLoveTime() != 0) {
            helper.fail("two weight-0 cows: parentA.inLoveTime expected 0 after cancel; got "
                    + parentA.getInLoveTime() + " — resetLove not called on parent A");
            return;
        }
        if (parentB.getInLoveTime() != 0) {
            helper.fail("two weight-0 cows: parentB.inLoveTime expected 0 after cancel; got "
                    + parentB.getInLoveTime() + " — resetLove not called on parent B");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "one weight-0 cow + one weight-1 cow fed wheat → no baby
     * (either-parent rule)". Pins that EITHER parent being sick blocks
     * breeding — not just both. Bug it catches: a {@code &&} where a
     * {@code ||} should be.
     */
    public static void mixedWeightCowPairBreedCancelled(GameTestHelper helper) {
        Cow parentA = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        Cow parentB = helper.spawnWithNoFreeWill(EntityType.COW, COW_PARTNER_REL);
        AnimalWeightAttachment.set(parentA, 0);
        AnimalWeightAttachment.set(parentB, 1);

        parentA.setInLoveTime(600);
        parentB.setInLoveTime(600);

        BabyEntitySpawnEvent event = new BabyEntitySpawnEvent(parentA, parentB, /* child */ null);
        NeoForge.EVENT_BUS.post(event);

        if (!event.isCanceled()) {
            helper.fail("mixed weight pair (0 + 1): BabyEntitySpawnEvent expected cancelled; was not — "
                    + "either-parent rule may be implemented as both-parent (&& instead of ||)");
            return;
        }
        // Both parents should still get reset, even though only one was sick.
        if (parentA.getInLoveTime() != 0) {
            helper.fail("mixed weight pair: sick parentA.inLoveTime expected 0; got " + parentA.getInLoveTime());
            return;
        }
        if (parentB.getInLoveTime() != 0) {
            helper.fail("mixed weight pair: healthy parentB.inLoveTime expected 0; got "
                    + parentB.getInLoveTime() + " — handler resets only the sick parent, "
                    + "leaving the healthy partner stuck in love mode");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "two weight-1 cows fed wheat by sim player → baby spawns".
     * Pins that two healthy parents are NOT blocked. Bug it catches:
     * an over-broad cancel that fires for all Cow/Pig/Sheep regardless of
     * weight (the bug would be a missing weight check in
     * {@code hasSickTargetParent}).
     */
    public static void healthyCowPairBreedNotCancelled(GameTestHelper helper) {
        Cow parentA = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        Cow parentB = helper.spawnWithNoFreeWill(EntityType.COW, COW_PARTNER_REL);
        AnimalWeightAttachment.set(parentA, 1);
        AnimalWeightAttachment.set(parentB, 1);

        parentA.setInLoveTime(600);
        parentB.setInLoveTime(600);

        BabyEntitySpawnEvent event = new BabyEntitySpawnEvent(parentA, parentB, /* child */ null);
        NeoForge.EVENT_BUS.post(event);

        if (event.isCanceled()) {
            helper.fail("two weight-1 cows: BabyEntitySpawnEvent was cancelled — "
                    + "healthy parents should not be blocked from breeding");
            return;
        }
        // inLoveTime should be UNCHANGED — the handler returns early without
        // resetting either parent.
        if (parentA.getInLoveTime() <= 0) {
            helper.fail("two weight-1 cows: parentA.inLoveTime expected > 0 (untouched); got "
                    + parentA.getInLoveTime() + " — handler reset healthy parents anyway");
            return;
        }
        if (parentB.getInLoveTime() <= 0) {
            helper.fail("two weight-1 cows: parentB.inLoveTime expected > 0 (untouched); got "
                    + parentB.getInLoveTime() + " — handler reset healthy parents anyway");
            return;
        }
        helper.succeed();
    }
}
