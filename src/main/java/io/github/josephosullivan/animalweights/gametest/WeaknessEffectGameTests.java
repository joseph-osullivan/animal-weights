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
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bodies for Tier-2 GameTests covering the {@link MobEffects#WEAKNESS} side of
 * the sick-state surface (added in run-005 task-7).
 *
 * <p>Spec source: {@code docs/workflow-runs/005-v0.2.0-parity/decisions.md} Q4
 * — "Sick visual tell: Both — mob effect AND render tint", with two effects
 * (Slowness + Weakness) configured in {@link SickStateHandler}. The Slowness
 * side was already covered by {@link SickStateGameTests}; this class pins the
 * Weakness addition without weakening the existing Slowness assertions.
 *
 * <p>Why a separate class: Slowness was the original sick effect (run-002),
 * Weakness is the run-005 add-on. Keeping them in separate test classes makes
 * the run-005 coverage boundary obvious in {@code ModGameTests} registration
 * blocks; failure logs will localise the regression to the right run.
 *
 * <p><b>Auto-eval race awareness:</b> The negative test
 * ({@link #weightOneTargetSpeciesGetsNeitherSlownessNorWeakness}) uses the same
 * isolated perfect-pen pattern as
 * {@link SickStateGameTests#weightOneCowHasNoSlowness}. The GameTest server
 * fires {@code DailyEvalHandler} on the first {@code LevelTickEvent.Post}; if
 * the cow happens to be in a "bad" pen (the empty test cell is bad),
 * weight 1 → 0 happens before the test can re-set, and slowness/weakness
 * legitimately apply — passing the test would be impossible. The isolated
 * perfect pen produces met=4 → +1, so weight goes 1 → 2 and never to 0.
 */
public final class WeaknessEffectGameTests {

    private WeaknessEffectGameTests() {}

    private static final BlockPos COW_REL = new BlockPos(2, 2, 2);

    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);
    /**
     * Base X 1_000_000 is outside the {@code SickStateGameTests} (800_000),
     * {@code WaterCauldronGameTests} (700_000), {@code BabySkipGameTests}
     * (600_000), and {@code VillageWaterBonusGameTests} (900_000) ranges.
     */
    private static final int ISOLATION_BASE_X = 1_000_000;
    private static final int ISOLATION_STRIDE = 1000;
    private static final int ISOLATION_Y = 128;

    private static BlockPos nextIsolatedPos() {
        return new BlockPos(ISOLATION_BASE_X + ISOLATION_X.getAndIncrement() * ISOLATION_STRIDE,
                ISOLATION_Y, 0);
    }

    /**
     * Build a 3×2 grass + adjacent water cauldron + torch overhead pen.
     * Calibrated to satisfy all four daily-eval conditions (light, water,
     * grazing, stretching with reach=6) when a cow is placed at the center.
     * Mirrors {@code SickStateGameTests.buildPerfectPen}.
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
     * Spec source: run-005 decisions.md Q4 — "Both — mob effect AND render
     * tint", configured as Slowness AND Weakness in
     * {@link SickStateHandler#onEntityTickPost}.
     *
     * <p>A weight-0 target species mob must have BOTH effects after the
     * handler's 8-tick throttle has had a chance to fire. Bug it catches:
     * a regression that drops one of the two effects (e.g. someone reverts
     * the Weakness branch thinking it's redundant with Slowness).
     *
     * <p>Uses {@code runAfterDelay(30L, ...)} rather than {@code succeedWhen}:
     * a previous attempt threw {@code GameTestAssertException} with tick=0
     * inside a {@code succeedWhen} lambda, which terminates the test
     * immediately at tick 0 rather than retrying — defeating the polling
     * loop. {@code runAfterDelay} delays the assertion by 30 ticks (~3
     * SickStateHandler throttle windows of 8 ticks each + headroom for the
     * 16-tick worst-case offset), then makes a one-shot assertion. If the
     * effects aren't applied by tick 30, the handler is genuinely broken
     * — not a timing flake. NOTE: {@code runAfterDelay} does NOT auto-succeed;
     * we must call {@code helper.succeed()} explicitly at the end of the
     * lambda once all assertions pass.
     */
    public static void weightZeroTargetSpeciesGetsBothSlownessAndWeakness(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        cow.setNoGravity(true); // 3x3 empty template has no floor — prevent fall race
        AnimalWeightAttachment.set(cow, 0);

        helper.runAfterDelay(30L, () -> {
            MobEffectInstance slowness = cow.getEffect(MobEffects.SLOWNESS);
            MobEffectInstance weakness = cow.getEffect(MobEffects.WEAKNESS);
            if (slowness == null) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 cow missing SLOWNESS effect — sick-state slowness branch broken"),
                        0);
            }
            if (weakness == null) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 cow missing WEAKNESS effect — run-005 task-7 weakness branch "
                                        + "(decisions.md Q4 'Both — mob effect AND render tint') is not firing"),
                        0);
            }
            // Both must have amplifier 0 (level I) per SICK_EFFECT_AMPLIFIER constant.
            if (slowness.getAmplifier() != 0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "SLOWNESS amplifier expected 0; got " + slowness.getAmplifier()),
                        0);
            }
            if (weakness.getAmplifier() != 0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "WEAKNESS amplifier expected 0; got " + weakness.getAmplifier()),
                        0);
            }
            // runAfterDelay does not auto-succeed; must call succeed() explicitly.
            helper.succeed();
        });
    }

    /**
     * Spec source: same Q4 — the two sick effects must NOT fire when the
     * animal is healthy (weight >= 1). Pins the over-broad-effect bug
     * symmetrically across both Slowness AND Weakness — the original
     * {@link SickStateGameTests#weightOneCowHasNoSlowness} only checks
     * Slowness, so a Weakness-applies-to-all-weights bug would slip past it.
     *
     * <p>Layout: isolated perfect pen at base X=1_000_000+. Dawn eval (if it
     * fires during the 50-tick wait) sees met=4 → +1, so weight goes 1 → 2
     * and never to 0 — the sick branch can never trigger from drift. See
     * class javadoc for the auto-eval race rationale.
     *
     * <p>Wait 50 ticks: longer than the 40-tick effect duration, so any
     * spuriously-applied effect from the spawn-tick race would have expired
     * by check time. Same idiom as
     * {@link SickStateGameTests#weightOneCowHasNoSlowness}.
     */
    public static void weightOneTargetSpeciesGetsNeitherSlownessNorWeakness(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pen = nextIsolatedPos();
        buildPerfectPen(level, pen);

        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        cow.snapTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0F, 0F);
        AnimalWeightAttachment.set(cow, 1);

        helper.runAfterDelay(50L, () -> {
            int weight = AnimalWeightAttachment.get(cow);
            MobEffectInstance slowness = cow.getEffect(MobEffects.SLOWNESS);
            MobEffectInstance weakness = cow.getEffect(MobEffects.WEAKNESS);
            if (slowness != null) {
                helper.fail("weight-" + weight + " cow has SLOWNESS — isSick predicate is over-broad "
                        + "(should only trigger on weight 0; amp=" + slowness.getAmplifier()
                        + ", dur=" + slowness.getDuration() + ")");
                return;
            }
            if (weakness != null) {
                helper.fail("weight-" + weight + " cow has WEAKNESS — run-005 task-7 weakness branch is over-broad "
                        + "(should only trigger on weight 0; amp=" + weakness.getAmplifier()
                        + ", dur=" + weakness.getDuration() + ")");
                return;
            }
            // Weight may have legitimately climbed to 2 if dawn eval ran — that's
            // a valid healthy outcome, not a regression. Anything >= 1 is fine.
            if (weight < 1) {
                helper.fail("cow weight unexpectedly dropped below 1 in perfect pen: weight=" + weight);
                return;
            }
            helper.succeed();
        });
    }
}
