package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.event.DailyEvalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier-2 GameTests for Chicken coverage, asserting the spec rows in run-003
 * {@code design.md} "Chicken" coverage targets.
 *
 * <p>Mirrors the structure of {@link DailyEvalGameTests} for daily-eval cases
 * (private isolated world location per test, perfect-pen helper, etc.) and
 * the direct event-posting pattern from {@link DropScalingGameTests} for drop
 * scaling. The slowness test mirrors {@link SickStateGameTests}.
 *
 * <p>Spec-anchored expectations:
 * <ul>
 *   <li>Daily eval: chicken treated as a target species, gains/loses weight
 *       like cow under the same condition rules. Chicken is also part of the
 *       crowding species set.</li>
 *   <li>Drops: primary set = {@code CHICKEN, COOKED_CHICKEN, FEATHER}. Bonus
 *       formula {@code max(0, weight-1)} applies uniformly.</li>
 *   <li>Sick state: weight-0 chicken gets SLOWNESS and cannot breed.</li>
 * </ul>
 */
public final class ChickenGameTests {

    private ChickenGameTests() {}

    /**
     * Per-class isolated-position counter, offset from {@link DailyEvalGameTests}'s
     * range. Using a high base X (200_000) keeps chicken daily-eval pens out of
     * the cow tests' 100_000–199_000 corridor so 8-radius scans never overlap.
     */
    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);

    private static final int ISOLATION_Y = 128;
    private static final int ISOLATION_BASE_X = 200_000;
    private static final int ISOLATION_STRIDE = 1000;

    private static final BlockPos VICTIM_REL = new BlockPos(2, 2, 2);
    private static final BlockPos PARTNER_REL = new BlockPos(2, 2, 4);

    private static BlockPos nextIsolatedPos() {
        int n = ISOLATION_X.getAndIncrement();
        return new BlockPos(ISOLATION_BASE_X + n * ISOLATION_STRIDE, ISOLATION_Y, 0);
    }

    private static void setAbsBlock(ServerLevel level, BlockPos abs, BlockState state) {
        level.setBlock(abs, state, 3);
    }

    /**
     * Build a "perfect pen" (all four conditions satisfied) around
     * {@code center}, calibrated for the BFS reachability spec from run-004.
     * See {@link DailyEvalGameTests#buildPerfectPen} for the design rationale.
     */
    private static void buildPerfectPen(ServerLevel level, BlockPos center) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                setAbsBlock(level, center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        setAbsBlock(level, center.offset(-1, -1, 0), Blocks.WATER_CAULDRON.defaultBlockState());
        setAbsBlock(level, center.offset(3, -1, 0), Blocks.WATER_CAULDRON.defaultBlockState());
        setAbsBlock(level, center.above(), Blocks.TORCH.defaultBlockState());
    }

    private static ItemEntity newDrop(GameTestHelper helper, BlockPos rel, net.minecraft.world.item.Item item, int count) {
        BlockPos abs = helper.absolutePos(rel);
        return new ItemEntity(helper.getLevel(),
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                new ItemStack(item, count));
    }

    // ----------------------------------------------------------------------
    // Daily evaluation — spec row "chicken_in_perfect_pen_weight_one_to_two"
    // ----------------------------------------------------------------------

    /**
     * Spec: a weight-1 chicken alone in a perfect pen (grass + water source +
     * torch) gains +1. Pins that Chicken is in the target-species list for
     * {@link DailyEvalHandler#runEvaluation}.
     */
    public static void chickenInPerfectPenWeightOneToTwo(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            chicken.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(chicken, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(chicken);
            if (weight != 2) {
                helper.fail("weight-1 chicken in perfect pen expected 2 after eval; got "
                        + weight + " — Chicken may be missing from DailyEvalHandler target list");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec: a weight-1 chicken in a crowded pen (next to another chicken; no
     * other conditions met) decreases. Two conditions can be met (grass +
     * stretching fails because of partner), so we use a minimal pen with stone
     * floor: zero conditions → -2 → 0.
     */
    public static void chickenInCrowdedPenDecreasesWeight(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        Chicken partner = helper.spawnWithNoFreeWill(EntityType.CHICKEN, PARTNER_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            chicken.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            partner.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            // Stone floor, no water, no torch → 0 of 4 conditions when crowded.
            setAbsBlock(level, pen.below(), Blocks.STONE.defaultBlockState());
            AnimalWeightAttachment.set(chicken, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(chicken);
            if (weight != 0) {
                helper.fail("weight-1 chicken in crowded stone pen expected 0 (-2 delta); got "
                        + weight + " — Chicken weight may not be decremented");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec: "chicken_next_to_cow_loses_stretching" — a chicken next to a cow
     * in an otherwise perfect pen sees 3 of 4 conditions met (no stretching) →
     * delta 0 → weight unchanged. Pins that cow counts as crowding for chicken.
     */
    public static void chickenNextToCowLosesStretching(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, PARTNER_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            chicken.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            cow.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(chicken, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(chicken);
            // 3 conditions met → delta 0 → weight 1.
            if (weight != 1) {
                helper.fail("weight-1 chicken next to cow in perfect pen expected 1 (3 conditions); got "
                        + weight + " — Cow may not count as crowding for Chicken-side eval");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec: "chicken_next_to_rabbit_loses_stretching" — a chicken next to a
     * rabbit in an otherwise perfect pen should fail stretching. New cross-
     * species crowding rule from run-003 (rabbit added to crowding set).
     */
    public static void chickenNextToRabbitLosesStretching(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, PARTNER_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            chicken.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            rabbit.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(chicken, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(chicken);
            if (weight != 1) {
                helper.fail("weight-1 chicken next to rabbit in perfect pen expected 1 (3 conditions); got "
                        + weight + " — Rabbit may be missing from the crowding species set");
                return;
            }
            helper.succeed();
        });
    }

    // ----------------------------------------------------------------------
    // Drop scaling
    // ----------------------------------------------------------------------

    /**
     * Spec: "kill_weight_one_chicken_drops_baseline" — weight 1 (default)
     * means bonus 0; raw chicken and feather counts unchanged.
     */
    public static void killWeightOneChickenDropsBaseline(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        AnimalWeightAttachment.set(chicken, 1);

        ItemEntity raw = newDrop(helper, VICTIM_REL, Items.CHICKEN, 1);
        ItemEntity feather = newDrop(helper, VICTIM_REL, Items.FEATHER, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(raw, feather));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(chicken,
                helper.getLevel().damageSources().generic(), drops, true));

        if (raw.getItem().getCount() != 1) {
            helper.fail("weight-1 chicken: raw_chicken expected 1 (unchanged); got "
                    + raw.getItem().getCount() + " — bonus accidentally fires at weight 1");
            return;
        }
        if (feather.getItem().getCount() != 1) {
            helper.fail("weight-1 chicken: feather expected 1; got " + feather.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    /**
     * Spec: "kill_weight_four_chicken_drops_three_extra_raw_chicken_and_feather"
     * — weight 4 → multiplicative scaling → raw_chicken * 4, feather * 4.
     */
    public static void killWeightFourChickenDropsThreeExtraRawChickenAndFeather(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        AnimalWeightAttachment.set(chicken, 4);

        ItemEntity raw = newDrop(helper, VICTIM_REL, Items.CHICKEN, 1);
        ItemEntity feather = newDrop(helper, VICTIM_REL, Items.FEATHER, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(raw, feather));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(chicken,
                helper.getLevel().damageSources().generic(), drops, true));

        if (raw.getItem().getCount() != 4) {
            helper.fail("weight-4 chicken: raw_chicken expected 1 * 4 = 4; got "
                    + raw.getItem().getCount() + " — CHICKEN missing from primary drop set");
            return;
        }
        if (feather.getItem().getCount() != 4) {
            helper.fail("weight-4 chicken: feather expected 1 * 4 = 4; got "
                    + feather.getItem().getCount() + " — FEATHER missing from primary drop set");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec: "kill_weight_eight_chicken_drops_seven_extra" — multiplicative
     * scaling means weight 8 yields {@code count * 8}. With a baseline of 1
     * each, the resulting counts are 8 each.
     */
    public static void killWeightEightChickenDropsSevenExtra(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        AnimalWeightAttachment.set(chicken, 8);

        ItemEntity raw = newDrop(helper, VICTIM_REL, Items.CHICKEN, 1);
        ItemEntity feather = newDrop(helper, VICTIM_REL, Items.FEATHER, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(raw, feather));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(chicken,
                helper.getLevel().damageSources().generic(), drops, true));

        if (raw.getItem().getCount() != 8) {
            helper.fail("weight-8 chicken: raw_chicken expected 1 * 8 = 8 (multiplicative); got "
                    + raw.getItem().getCount());
            return;
        }
        if (feather.getItem().getCount() != 8) {
            helper.fail("weight-8 chicken: feather expected 1 * 8 = 8 (multiplicative); got "
                    + feather.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    // ----------------------------------------------------------------------
    // Sick state
    // ----------------------------------------------------------------------

    /**
     * Spec: "weight_zero_chicken_has_slowness" — weight-0 chicken receives
     * SLOWNESS within 2 ticks. Pins that Chicken is in the sick-state
     * target-species filter.
     */
    public static void weightZeroChickenHasSlowness(GameTestHelper helper) {
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        chicken.setNoGravity(true);
        AnimalWeightAttachment.set(chicken, 0);

        helper.succeedWhen(() -> {
            MobEffectInstance effect = chicken.getEffect(MobEffects.SLOWNESS);
            if (effect == null) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 chicken has no SLOWNESS yet"),
                        0);
            }
            if (effect.getAmplifier() != 0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 chicken SLOWNESS amplifier expected 0; got "
                                        + effect.getAmplifier()),
                        0);
            }
        });
    }

    /**
     * Spec: "weight_zero_chicken_cannot_breed" — weight-0 chicken parent
     * blocks {@link BabyEntitySpawnEvent} for a chicken pair.
     */
    public static void weightZeroChickenCannotBreed(GameTestHelper helper) {
        Chicken parentA = helper.spawnWithNoFreeWill(EntityType.CHICKEN, VICTIM_REL);
        Chicken parentB = helper.spawnWithNoFreeWill(EntityType.CHICKEN, PARTNER_REL);
        AnimalWeightAttachment.set(parentA, 0);
        AnimalWeightAttachment.set(parentB, 1);

        parentA.setInLoveTime(600);
        parentB.setInLoveTime(600);

        BabyEntitySpawnEvent event = new BabyEntitySpawnEvent(parentA, parentB, null);
        NeoForge.EVENT_BUS.post(event);

        if (!event.isCanceled()) {
            helper.fail("weight-0 + weight-1 chicken pair: BabyEntitySpawnEvent expected cancelled; "
                    + "was not — Chicken may be missing from sick-breed-cancel filter");
            return;
        }
        if (parentA.getInLoveTime() != 0) {
            helper.fail("sick chicken parentA.inLoveTime expected 0; got " + parentA.getInLoveTime());
            return;
        }
        if (parentB.getInLoveTime() != 0) {
            helper.fail("healthy chicken parentB.inLoveTime expected 0; got " + parentB.getInLoveTime()
                    + " — handler should reset both parents");
            return;
        }
        helper.succeed();
    }
}
