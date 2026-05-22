package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.event.DropScalingHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bodies for Tier-2 GameTests covering the village/water passive {@code +1}
 * primary-drop bonus added in run-005 task-8 (decisions.md Q1 item 5).
 *
 * <p>Spec source: {@code docs/workflow-runs/005-v0.2.0-parity/decisions.md} Q1:
 * "Village/water passive bonus — +1 primary loot roll for animals near a
 * village ({@code ServerLevel#isVillage}) or water source. Independent of
 * weight. Cached per-tick on the attachment."
 *
 * <p>The implementation lives in
 * {@link DropScalingHandler#onLivingDrops} as a {@code stack.grow(1)} applied
 * AFTER the multiplicative weight scaling. It does <b>not</b> apply to sick
 * mobs (weight 0 takes an early return through
 * {@code applySickDropReduction}).
 *
 * <p><b>Village vs. water:</b> only the water side of the OR clause is
 * GameTest-covered here. Village proximity via
 * {@link ServerLevel#isVillage(BlockPos)} requires a meeting POI structure
 * (bell + 32 villager-claimed beds nearby) which is impractical to set up in
 * a GameTest cell. The village branch is deferred to manual playtest per
 * the same playtest list as the {@code /animalweights} command.
 *
 * <p><b>Isolation pattern:</b> the production scan uses a {@code 12^3} AABB
 * around the cow's {@code blockPosition()}, which can leak across test cells
 * if neighbouring tests place water. Each test here teleports its cow to a
 * unique isolated position (base X=900_000, stride 1000) so the scan AABBs
 * never overlap. Mirrors the pattern from {@link WaterCauldronGameTests} and
 * {@link BabySkipGameTests}.
 *
 * <p><b>Setup-inside-delay ordering (load-bearing):</b> each test does
 * {@code teleportToIsolation} + {@code setBlock} INSIDE the
 * {@code runAfterDelay(2L, ...)} callback. The {@code setup_ticks: 2} in the
 * test_instance JSON gets the isolated chunks loaded before our setBlock
 * runs; doing the writes before the delay can no-op against an unloaded
 * chunk. Matches the {@link WaterCauldronGameTests} sequence.
 */
public final class VillageWaterBonusGameTests {

    private VillageWaterBonusGameTests() {}

    private static final BlockPos COW_REL = new BlockPos(2, 2, 2);

    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);
    private static final int ISOLATION_Y = 128;
    /**
     * Base X of 900_000 keeps these tests well outside the cell grid AND
     * outside the ranges used by {@link BabySkipGameTests} (600_000),
     * {@link WaterCauldronGameTests} (700_000), and
     * {@link SickStateGameTests} (800_000). Stride 1000 leaves a 988-block
     * gap between adjacent test cows, which dwarfs the {@code 12^3} scan AABB.
     */
    private static final int ISOLATION_BASE_X = 900_000;
    private static final int ISOLATION_STRIDE = 1000;

    private static BlockPos nextIsolatedPos() {
        int n = ISOLATION_X.getAndIncrement();
        return new BlockPos(ISOLATION_BASE_X + n * ISOLATION_STRIDE, ISOLATION_Y, 0);
    }

    private static void teleportToIsolation(Cow cow, BlockPos abs) {
        cow.snapTo(abs.getX() + 0.5, (double) abs.getY(), abs.getZ() + 0.5, 0.0F, 0.0F);
    }

    private static ItemEntity newDrop(ServerLevel level, BlockPos abs, ItemStack stack) {
        return new ItemEntity(level,
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, stack);
    }

    /**
     * Post a {@link LivingDropsEvent} synchronously. The
     * {@link DropScalingHandler} listener on the NeoForge bus runs inline and
     * mutates {@code drops} before this call returns.
     */
    private static void postDropsEventGeneric(GameTestHelper helper, LivingEntity victim, List<ItemEntity> drops) {
        LivingDropsEvent event = new LivingDropsEvent(victim,
                helper.getLevel().damageSources().generic(),
                drops, /* recentlyHit */ true);
        NeoForge.EVENT_BUS.post(event);
    }

    /**
     * Spec source: decisions.md Q1 item 5 — "+1 primary loot roll for
     * animals near a water source". With weight 1 (multiplier == 1, no
     * scaling change) and a water source within the configured
     * {@code village_water_radius} AABB (default 6), the cow's beef
     * drop must gain exactly +1 over vanilla: base 1 → final 2.
     *
     * <p>Bug it catches: the water-side branch of {@code isNearVillageOrWater}
     * is broken (always false), or {@code stack.grow(1)} is gated on
     * {@code multiplier >= 2} so weight-1 cows silently miss the bonus.
     */
    public static void killWeightOneCowNearWaterDropsOneExtraBeef(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // Place a water source block well within the 6-block radius. Sit
            // it 3 blocks east of the cow.
            level.setBlock(pen.offset(3, 0, 0), Blocks.WATER.defaultBlockState(), 3);
            AnimalWeightAttachment.set(cow, 1);

            ItemStack beefStack = new ItemStack(Items.BEEF, 1);
            ItemEntity beef = newDrop(level, pen, beefStack);
            List<ItemEntity> drops = new ArrayList<>(List.of(beef));
            postDropsEventGeneric(helper, cow, drops);

            int count = beef.getItem().getCount();
            // Weight 1 → no multiplicative change. Village/water bonus → +1.
            // Final count: 1 + 1 = 2.
            if (count != 2) {
                helper.fail("weight-1 cow near water: beef expected 1 + 1 (water bonus) = 2; got " + count
                        + " — village/water +1 not applied at weight 1 (likely gated on multiplier>=2 incorrectly)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec source: decisions.md Q1 item 5 — sanity for the negative case.
     * A weight-1 cow with NO water in the AABB drops vanilla baseline (1
     * beef → 1 beef, no bonus).
     *
     * <p>Bug it catches: {@code isNearVillageOrWater} always returns true
     * (e.g. someone short-circuited the implementation while debugging), so
     * every cow silently gets a free +1.
     */
    public static void killWeightOneCowAwayFromWaterDropsVanilla(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // No water placed. The isolated position is 900_000+ on X, way
            // past any vanilla water generation (the GameTest server only
            // generates the structure-template chunks).
            AnimalWeightAttachment.set(cow, 1);

            ItemStack beefStack = new ItemStack(Items.BEEF, 1);
            ItemEntity beef = newDrop(level, pen, beefStack);
            List<ItemEntity> drops = new ArrayList<>(List.of(beef));
            postDropsEventGeneric(helper, cow, drops);

            int count = beef.getItem().getCount();
            // Weight 1 + no water = vanilla baseline = 1.
            if (count != 1) {
                helper.fail("weight-1 cow away from water: beef expected 1 (vanilla baseline); got " + count
                        + " — isNearVillageOrWater is returning true with no water nearby (always-true bug, "
                        + "or test isolation leaking water from a neighbouring cell)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec source: {@link DropScalingHandler#onLivingDrops} class javadoc:
     * "village/water passive bonus ... does <b>not</b> apply to sick mobs
     * (weight 0) — they take the early-return through
     * {@code applySickDropReduction} before the bonus check."
     *
     * <p>A weight-0 cow near water is still sick and STILL gets the
     * cap-to-1 sick-drop reduction, NOT the +1 bonus. Bug it catches:
     * someone moves the {@code isNearVillageOrWater} check above the sick
     * early-return, accidentally rewarding sick mobs with both effects.
     *
     * <p>Spec-first reasoning: a vanilla cow drops 1-3 beef. A weight-0 cow
     * with the sick reduction caps to count <= 1. If the bonus were applied
     * AFTER the sick reduction (a future regression), final count could be 2.
     * If the bonus were applied INSTEAD of the sick reduction, the cap
     * wouldn't fire and a count-3 stack would survive. The test asserts the
     * cap-to-1 invariant for any SURVIVING primary drop — matches the
     * {@link SickDropReductionGameTests#sickCowDropsCappedToOnePerPrimary}
     * pattern.
     */
    public static void killWeightZeroCowNearWaterGetsNoVillageBonus(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            level.setBlock(pen.offset(3, 0, 0), Blocks.WATER.defaultBlockState(), 3);
            AnimalWeightAttachment.set(cow, 0);

            // Use a high count (8) so that if the bonus AND no cap fired,
            // we'd see 8 or 9 in the final count — clear distinction from
            // the cap-to-1 invariant.
            ItemStack beefStack = new ItemStack(Items.BEEF, 8);
            ItemEntity beef = newDrop(level, pen, beefStack);
            List<ItemEntity> drops = new ArrayList<>(List.of(beef));
            postDropsEventGeneric(helper, cow, drops);

            // If the beef survived the probabilistic 50% removal, its count
            // must be <= 1 (cap invariant). It must definitely not be 9
            // (cap + bonus combo) or > 8 (bonus without cap).
            for (ItemEntity drop : drops) {
                if (drop.getItem().is(Items.BEEF)) {
                    int count = drop.getItem().getCount();
                    if (count > 1) {
                        helper.fail("weight-0 cow near water: surviving beef has count " + count
                                + " (expected <= 1) — sick-mob branch is bypassed when water is near, "
                                + "or village/water bonus is fired BEFORE the sick early-return");
                        return;
                    }
                }
            }
            helper.succeed();
        });
    }

    /**
     * Spec source: decisions.md Q2 + Q1 item 5 — the multiplicative weight
     * formula AND the +1 village/water bonus stack. Formula:
     * {@code (base * weight) + 1}. For weight 4, base 1 beef: 1 * 4 + 1 = 5.
     *
     * <p>This is the canonical "do both layers compose correctly" test.
     * Bug it catches: the bonus is applied to the BASE count before
     * multiplication ({@code (1 + 1) * 4 = 8}), or the multiplier is applied
     * to the bonus ({@code 1 * 4 + 1 * 4 = 8}), or one layer no-ops when the
     * other layer is active.
     */
    public static void killWeightFourCowNearWaterDropsMultiplicativePlusOne(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            level.setBlock(pen.offset(3, 0, 0), Blocks.WATER.defaultBlockState(), 3);
            AnimalWeightAttachment.set(cow, 4);

            ItemStack beefStack = new ItemStack(Items.BEEF, 1);
            ItemEntity beef = newDrop(level, pen, beefStack);
            List<ItemEntity> drops = new ArrayList<>(List.of(beef));
            postDropsEventGeneric(helper, cow, drops);

            int count = beef.getItem().getCount();
            // Expected: 1 * 4 (weight multiplier) + 1 (village/water bonus) = 5.
            if (count != 5) {
                helper.fail("weight-4 cow near water: beef expected (1*4)+1 = 5; got " + count
                        + " — multiplicative scaling + village/water bonus not composing per spec "
                        + "(possible bugs: +1 applied pre-mult → 8, mult applied to +1 too → 8, "
                        + "or one layer no-opped)");
                return;
            }
            helper.succeed();
        });
    }
}
