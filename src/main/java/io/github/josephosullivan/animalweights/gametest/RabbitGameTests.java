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
 * Tier-2 GameTests for Rabbit coverage, asserting the spec rows in run-003
 * {@code design.md} "Rabbit" coverage targets.
 *
 * <p>Spec-anchored expectations:
 * <ul>
 *   <li>Daily eval: rabbit treated as a target species; rabbit is also part of
 *       the crowding species set per run-003.</li>
 *   <li>Drops: primary set = {@code RABBIT, COOKED_RABBIT, RABBIT_HIDE}.
 *       {@code RABBIT_FOOT} is a rare drop and is intentionally NOT scaled —
 *       this is critical: weight must not act as a Looting amplifier on rare
 *       drops.</li>
 *   <li>Sick state: weight-0 rabbit gets SLOWNESS and cannot breed.</li>
 * </ul>
 */
public final class RabbitGameTests {

    private RabbitGameTests() {}

    /**
     * Per-class isolated-position counter. Offset from cow tests (100_000) and
     * chicken tests (200_000); rabbit uses 300_000 base X.
     */
    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);

    private static final int ISOLATION_Y = 128;
    private static final int ISOLATION_BASE_X = 300_000;
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
    // Daily evaluation
    // ----------------------------------------------------------------------

    /**
     * Spec: "rabbit_in_perfect_pen_weight_one_to_two" — weight-1 rabbit alone
     * in a perfect pen gains +1.
     */
    public static void rabbitInPerfectPenWeightOneToTwo(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            rabbit.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(rabbit, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(rabbit);
            if (weight != 2) {
                helper.fail("weight-1 rabbit in perfect pen expected 2 after eval; got "
                        + weight + " — Rabbit may be missing from DailyEvalHandler target list");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec: "rabbit_in_crowded_pen_decreases_weight" — weight-1 rabbit in a
     * stone pen next to another rabbit drops to 0. Zero conditions → -2.
     */
    public static void rabbitInCrowdedPenDecreasesWeight(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        Rabbit partner = helper.spawnWithNoFreeWill(EntityType.RABBIT, PARTNER_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            rabbit.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            partner.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            setAbsBlock(level, pen.below(), Blocks.STONE.defaultBlockState());
            AnimalWeightAttachment.set(rabbit, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(rabbit);
            if (weight != 0) {
                helper.fail("weight-1 rabbit in crowded stone pen expected 0 (-2 delta); got "
                        + weight + " — Rabbit weight may not be decremented");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec: "rabbit_next_to_chicken_loses_stretching" — symmetric to chicken's
     * mirror test. Confirms rabbit-side eval sees chicken as crowding AND
     * that rabbit itself is the eval target.
     */
    public static void rabbitNextToChickenLosesStretching(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, PARTNER_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            rabbit.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            chicken.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(rabbit, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(rabbit);
            // 3 conditions (grass + water + light, no stretching) → delta 0 → 1.
            if (weight != 1) {
                helper.fail("weight-1 rabbit next to chicken in perfect pen expected 1 (3 conditions); got "
                        + weight + " — crowding check or rabbit-as-eval-target broken");
                return;
            }
            helper.succeed();
        });
    }

    // ----------------------------------------------------------------------
    // Drop scaling
    // ----------------------------------------------------------------------

    /**
     * Spec: "kill_weight_one_rabbit_drops_baseline" — weight 1 → bonus 0 →
     * unchanged primary drops.
     */
    public static void killWeightOneRabbitDropsBaseline(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        AnimalWeightAttachment.set(rabbit, 1);

        ItemEntity raw = newDrop(helper, VICTIM_REL, Items.RABBIT, 1);
        ItemEntity hide = newDrop(helper, VICTIM_REL, Items.RABBIT_HIDE, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(raw, hide));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(rabbit,
                helper.getLevel().damageSources().generic(), drops, true));

        if (raw.getItem().getCount() != 1) {
            helper.fail("weight-1 rabbit: raw_rabbit expected 1 (unchanged); got "
                    + raw.getItem().getCount());
            return;
        }
        if (hide.getItem().getCount() != 1) {
            helper.fail("weight-1 rabbit: rabbit_hide expected 1 (unchanged); got "
                    + hide.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    /**
     * Spec: "kill_weight_four_rabbit_drops_three_extra_raw_rabbit_and_hide" —
     * weight 4 → bonus 3 → raw_rabbit + 3, rabbit_hide + 3.
     */
    public static void killWeightFourRabbitDropsThreeExtraRawRabbitAndHide(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        AnimalWeightAttachment.set(rabbit, 4);

        ItemEntity raw = newDrop(helper, VICTIM_REL, Items.RABBIT, 1);
        ItemEntity hide = newDrop(helper, VICTIM_REL, Items.RABBIT_HIDE, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(raw, hide));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(rabbit,
                helper.getLevel().damageSources().generic(), drops, true));

        if (raw.getItem().getCount() != 4) {
            helper.fail("weight-4 rabbit: raw_rabbit expected 1 + 3 = 4; got "
                    + raw.getItem().getCount() + " — RABBIT missing from primary drop set");
            return;
        }
        if (hide.getItem().getCount() != 4) {
            helper.fail("weight-4 rabbit: rabbit_hide expected 1 + 3 = 4; got "
                    + hide.getItem().getCount() + " — RABBIT_HIDE missing from primary drop set");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec: "kill_weight_four_rabbit_does_not_amplify_rabbit_foot" — the
     * critical guard. Construct a drops list that includes a {@code RABBIT_FOOT}
     * stack (simulating a successful 10% roll). Even at weight 4 (bonus 3),
     * the foot stack count must stay at 1 because RABBIT_FOOT is NOT in the
     * rabbit primary drop set. Pins that weight is not a Looting amplifier on
     * rare drops.
     */
    public static void killWeightFourRabbitDoesNotAmplifyRabbitFoot(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        AnimalWeightAttachment.set(rabbit, 4);

        ItemEntity raw = newDrop(helper, VICTIM_REL, Items.RABBIT, 1);
        ItemEntity foot = newDrop(helper, VICTIM_REL, Items.RABBIT_FOOT, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(raw, foot));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(rabbit,
                helper.getLevel().damageSources().generic(), drops, true));

        // The always-rolled primary should still scale.
        if (raw.getItem().getCount() != 4) {
            helper.fail("weight-4 rabbit: raw_rabbit expected 1 + 3 = 4; got "
                    + raw.getItem().getCount() + " — primary drop scaling regressed");
            return;
        }
        // The rare foot must NOT scale.
        if (foot.getItem().getCount() != 1) {
            helper.fail("weight-4 rabbit: rabbit_foot expected 1 (rare drop, no scaling); got "
                    + foot.getItem().getCount() + " — RABBIT_FOOT incorrectly added to primary drop set; "
                    + "weight is acting as a Looting amplifier on rare drops");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec: "kill_weight_eight_rabbit_drops_seven_extra" — original linear
     * formula expected +7. Updated for run-004 curve: w=8 → +11.
     */
    public static void killWeightEightRabbitDropsSevenExtra(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        AnimalWeightAttachment.set(rabbit, 8);

        ItemEntity raw = newDrop(helper, VICTIM_REL, Items.RABBIT, 1);
        ItemEntity hide = newDrop(helper, VICTIM_REL, Items.RABBIT_HIDE, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(raw, hide));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(rabbit,
                helper.getLevel().damageSources().generic(), drops, true));

        if (raw.getItem().getCount() != 12) {
            helper.fail("weight-8 rabbit: raw_rabbit expected 1 + 11 = 12 (run-004 curve); got "
                    + raw.getItem().getCount());
            return;
        }
        if (hide.getItem().getCount() != 12) {
            helper.fail("weight-8 rabbit: rabbit_hide expected 1 + 11 = 12 (run-004 curve); got "
                    + hide.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    // ----------------------------------------------------------------------
    // Sick state
    // ----------------------------------------------------------------------

    /**
     * Spec: "weight_zero_rabbit_has_slowness" — weight-0 rabbit receives
     * SLOWNESS within 2 ticks.
     */
    public static void weightZeroRabbitHasSlowness(GameTestHelper helper) {
        Rabbit rabbit = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        rabbit.setNoGravity(true);
        AnimalWeightAttachment.set(rabbit, 0);

        helper.succeedWhen(() -> {
            MobEffectInstance effect = rabbit.getEffect(MobEffects.SLOWNESS);
            if (effect == null) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 rabbit has no SLOWNESS yet — "
                                        + "Rabbit may be missing from SickStateHandler target list"),
                        0);
            }
            if (effect.getAmplifier() != 0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 rabbit SLOWNESS amplifier expected 0 (Slowness I); got "
                                        + effect.getAmplifier()),
                        0);
            }
        });
    }

    /**
     * Spec: "weight_zero_rabbit_cannot_breed" — weight-0 rabbit parent
     * blocks {@link BabyEntitySpawnEvent} for a rabbit pair.
     */
    public static void weightZeroRabbitCannotBreed(GameTestHelper helper) {
        Rabbit parentA = helper.spawnWithNoFreeWill(EntityType.RABBIT, VICTIM_REL);
        Rabbit parentB = helper.spawnWithNoFreeWill(EntityType.RABBIT, PARTNER_REL);
        AnimalWeightAttachment.set(parentA, 0);
        AnimalWeightAttachment.set(parentB, 1);

        parentA.setInLoveTime(600);
        parentB.setInLoveTime(600);

        BabyEntitySpawnEvent event = new BabyEntitySpawnEvent(parentA, parentB, null);
        NeoForge.EVENT_BUS.post(event);

        if (!event.isCanceled()) {
            helper.fail("weight-0 + weight-1 rabbit pair: BabyEntitySpawnEvent expected cancelled; "
                    + "was not — Rabbit may be missing from sick-breed-cancel filter");
            return;
        }
        if (parentA.getInLoveTime() != 0) {
            helper.fail("sick rabbit parentA.inLoveTime expected 0; got " + parentA.getInLoveTime());
            return;
        }
        if (parentB.getInLoveTime() != 0) {
            helper.fail("healthy rabbit parentB.inLoveTime expected 0; got " + parentB.getInLoveTime());
            return;
        }
        helper.succeed();
    }
}
