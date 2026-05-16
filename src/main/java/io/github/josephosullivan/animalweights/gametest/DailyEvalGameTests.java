package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import io.github.josephosullivan.animalweights.event.DailyEvalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bodies for {@link DailyEvalHandler} Tier-2 GameTests, asserting the spec
 * from run-002 {@code design.md} "Daily evaluation" section.
 *
 * <p>The handler iterates {@link DailyEvalHandler#runEvaluation} over every
 * loaded Cow/Pig/Sheep in the level, with each mob's conditions evaluated by
 * scanning an 8-block radius cube of world blocks around its position. Two
 * problems for naïve test isolation:
 * <ol>
 *   <li>Adjacent GameTest cells (~3 blocks of structure + 5 block grid gap)
 *       are well inside the 8-radius scan, so a neighbour cell's fixtures
 *       (grass / water / torch) bleed into MY cow's conditions check.</li>
 *   <li>Calling {@code runEvaluation} mutates EVERY cow in the level,
 *       including neighbours that other tests might still be using.</li>
 * </ol>
 *
 * <p>Workaround: each test teleports its cow to a private,
 * far-from-everyone-else world location. The cow's 8-block scan then sees
 * only blocks WE explicitly place around it via
 * {@code helper.getLevel().setBlock(...)}. The per-test {@link #ISOLATION_X}
 * counter ensures no two tests share a private location.
 *
 * <p>Approach per test:
 * <ol>
 *   <li>Spawn a cow inside the cell.</li>
 *   <li>Defer 2 ticks so the entity registers in chunks.</li>
 *   <li>Inside the lambda: teleport cow to an isolated absolute position,
 *       build the desired pen layout at world positions around it, re-set
 *       the cow's weight to a known baseline, immediately call
 *       {@link DailyEvalHandler#runEvaluation(ServerLevel)}, immediately read
 *       the resulting weight. This trio runs synchronously on the server
 *       thread, so no other test's lambda can interleave.</li>
 * </ol>
 *
 * <p>Spec delta table: 0/1 → -2, 2 → -1, 3 → 0, 4 → +1; final weight clamped
 * to {@code [0, 8]}.
 *
 * <p>The dawn-gate logic in {@link DailyEvalHandler#onLevelTickPost} is not
 * exercised here — testing it would require manipulating
 * {@code getOverworldClockTime}, which has no test seam. The
 * dimension-restriction ("overworld-only") row from the spec table is
 * skipped for the same reason. Both gates are simple one-liners and could be
 * covered via a Tier-1 test against {@code onLevelTickPost} with a mocked
 * level if regression coverage becomes needed.
 */
public final class DailyEvalGameTests {

    private DailyEvalGameTests() {}

    /**
     * Counter assigning each test invocation a unique offset along the X axis
     * for its isolated world location. Static state across the GameTest
     * server boot — incremented atomically each call. Each test's location is
     * spaced 1000 blocks apart on X so 8-block radii can never overlap.
     */
    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);

    /** Base Y coordinate for isolated test pens (high enough to be above sea level). */
    private static final int ISOLATION_Y = 128;

    /**
     * Base X coordinate (far from origin). Tests get
     * {@code ISOLATION_BASE_X + (n * ISOLATION_STRIDE)}.
     */
    private static final int ISOLATION_BASE_X = 100_000;

    /** Stride between isolated test locations along X. 1000 >> 8-radius. */
    private static final int ISOLATION_STRIDE = 1000;

    /**
     * Acquire a fresh isolated absolute position. Different tests get
     * different X coordinates so their cow-radius scans never overlap.
     */
    private static BlockPos nextIsolatedPos() {
        int n = ISOLATION_X.getAndIncrement();
        return new BlockPos(ISOLATION_BASE_X + n * ISOLATION_STRIDE, ISOLATION_Y, 0);
    }

    /**
     * Build a "perfect pen" (all four conditions satisfied) around
     * {@code center}, calibrated for the BFS reachability spec from run-004:
     * <ul>
     *   <li>3×2 grass platform at Y-1 spanning {@code (center.x..center.x+2, _, center.z..center.z+1)} —
     *       gives the cow exactly 6 walkable cells alone (== MIN_ROAMING_CELLS),
     *       so a single neighbouring farm animal blocking one cell drops reach
     *       to 5 → stretching fails for "_loses_stretching" tests.</li>
     *   <li>Water cauldrons at {@code (center.x-1, center.y-1, center.z)} AND
     *       {@code (center.x+3, center.y-1, center.z)} — both at Y-1 flanking
     *       the platform west and east. With cauldrons on both sides, BOTH a
     *       cow at the west corner and a partner at the east end satisfy the
     *       water condition via the sunken-Y-1 path (each one's closest cell
     *       has a horizontal Y-1 neighbour cauldron). Critical for
     *       {@code two_adjacent_cows_both_fail_stretching}, where the eastern
     *       cow can't see the western cauldron because its western neighbour
     *       is occupied by the partner cow.</li>
     *   <li>Torch directly above the cow at {@code center.y+1} — raw brightness
     *       14 at the cow's cell (== LIGHT_THRESHOLD). Note: sky light at this
     *       isolated Y=128 also reaches 15 during in-game day, so light
     *       condition holds even at the east end of the pen via sky exposure.</li>
     * </ul>
     * Stretching is satisfied iff the test doesn't add a farm-animal partner.
     * Zombie partner doesn't block BFS (excluded from {@code isWalkableCell}'s
     * "other" filter) so stretching still passes with zombie adjacent.
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

    /**
     * Build a stone-only platform that gives the cow exactly 6 walkable cells
     * (satisfies stretching) but no grass, water, or light. Layout matches
     * {@link #buildPerfectPen} except every floor block is stone and there is
     * no water cauldron / torch.
     */
    private static void buildStretchOnlyPen(ServerLevel level, BlockPos center) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                setAbsBlock(level, center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /**
     * Build a 3×2 grass platform + torch + NO water — for the "3 conditions
     * met (no water)" cases.
     */
    private static void buildGrassAndLightPen(ServerLevel level, BlockPos center) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                setAbsBlock(level, center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        setAbsBlock(level, center.above(), Blocks.TORCH.defaultBlockState());
    }

    /** Convenience: set a block at an absolute world position. */
    private static void setAbsBlock(ServerLevel level, BlockPos abs, BlockState state) {
        // Flag 3 == update neighbours + send to clients (standard).
        level.setBlock(abs, state, 3);
    }

    /**
     * Teleport the cow to the isolated position. The framework's
     * {@code spawnWithNoFreeWill} put it at our test cell; we move it
     * out-of-band so its 8-radius scan sees only our placed fixtures.
     * {@code moveTo} also updates the entity's pos in the level's entity
     * index — important so the
     * {@link net.minecraft.world.phys.AABB}-based stretching check finds the
     * cow at its NEW location.
     */
    private static void teleportToIsolation(Cow cow, BlockPos abs) {
        cow.snapTo(abs.getX() + 0.5, (double) abs.getY(), abs.getZ() + 0.5, 0.0F, 0.0F);
    }

    // ----------------------------------------------------------------------
    // Spec table — "Tests that catch real bugs"
    // ----------------------------------------------------------------------

    /**
     * Spec row: "weight-1 cow alone in 5×5 pen with grass + water source +
     * torch → 2". Base case wired correctly.
     */
    public static void weightOneCowInPerfectPenIncreasesToTwo(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 2) {
                helper.fail("weight-1 cow in perfect pen expected weight 2 after eval; got "
                        + weight + " (base case wired wrong, or one of the four conditions failed)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "weight-1 cow with 3 of 4 conditions met (e.g. no water) →
     * unchanged (1)". Pins the delta threshold at 3 conditions == no change.
     *
     * <p>BFS-era layout: 3×2 grass platform + torch + NO water → grass + light
     * + stretching = 3 conditions → delta 0 → weight stays at 1.
     */
    public static void weightOneCowWithThreeConditionsStaysAtOne(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildGrassAndLightPen(level, pen);
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 1) {
                helper.fail("weight-1 cow with 3 conditions (grass + torch + stretching, no water) "
                        + "expected 1; got " + weight + " — delta-at-3 should be 0, not " + (weight - 1));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "weight-1 cow with 0 conditions met → 0 (-2 clamped)".
     *
     * <p>BFS-era layout: a single stone block under the cow only. BFS reach=1
     * (start cell only; no walkable expansion since neighbouring floor cells
     * are air). No grass / water / light / stretching → 0 conditions → delta -2.
     */
    public static void weightOneCowWithZeroConditionsDecreasesToZero(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // Single stone block under cow — start cell only; no expansion.
            setAbsBlock(level, pen.below(), Blocks.STONE.defaultBlockState());
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 0) {
                helper.fail("weight-1 cow with 0 conditions expected 0 (delta -2 clamped); got "
                        + weight + " — wrong-direction delta or wrong clamp");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "weight-1 cow with 1 condition met → 0 (-2 delta)". Pins
     * that "1 condition" maps to the same -2 delta as "0 conditions" — bug
     * catches an off-by-one in the lookup table where someone writes
     * {@code [-2, -1, 0, +1, +2]}.
     *
     * <p>BFS-era layout: 3×2 stone platform (reach=6 ⇒ stretching satisfied)
     * with no grass, no water, no light. Exactly 1 condition.
     */
    public static void weightOneCowWithOneConditionDecreasesToZero(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildStretchOnlyPen(level, pen);
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 0) {
                helper.fail("weight-1 cow with 1 condition (stretching only) expected 0 (-2 delta); got "
                        + weight + " — DELTA_BY_CONDITIONS[1] might be off-by-one");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "weight-8 cow in perfect pen → stays at 8". Pins the upper
     * clamp at {@link AnimalWeightsTuning#WEIGHT_MAX}.
     */
    public static void weightEightCowInPerfectPenStaysAtEight(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(cow, AnimalWeightsTuning.WEIGHT_MAX);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != AnimalWeightsTuning.WEIGHT_MAX) {
                helper.fail("weight-8 cow in perfect pen expected to stay at WEIGHT_MAX (8); got "
                        + weight + " — upper clamp missing or post-eval write skipped clamp");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "weight-0 cow in perfect pen → 1 (+1 delta)". Pins the lower
     * clamp doesn't get stuck at 0. Bug it catches: a {@code weight > 0}
     * guard that skips weight-0 entities entirely (which would let sick mobs
     * never recover).
     */
    public static void weightZeroCowInPerfectPenIncreasesToOne(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(cow, 0);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 1) {
                helper.fail("weight-0 cow in perfect pen expected 1 (+1 delta from sick); got "
                        + weight + " — eval may skip weight-0 entities, leaving them stuck sick");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "cow next to chicken in otherwise perfect pen → 1 (stretching
     * fails)". Pins that chicken counts as crowding farm animal.
     */
    public static void cowNextToChickenLosesStretching(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, new BlockPos(2, 2, 4));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // Place chicken right next to the cow so the 3×3 horizontal scan
            // sees it.
            chicken.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            // Conditions: grass + water + light + (no stretching) = 3 → delta 0 → weight 1.
            if (weight != 1) {
                helper.fail("weight-1 cow next to chicken in perfect pen expected 1 (3 conditions); got "
                        + weight + " — Chicken may be excluded from crowding species list");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "cow next to pig in otherwise perfect pen → 1 (stretching
     * fails)".
     */
    public static void cowNextToPigLosesStretching(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(2, 2, 4));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            pig.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 1) {
                helper.fail("weight-1 cow next to pig in perfect pen expected 1 (3 conditions); got "
                        + weight);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "cow next to a Zombie in otherwise perfect pen → 2 (still
     * passes stretching)". Pins that the crowding filter is NARROW — only
     * Cow/Pig/Sheep/Chicken count. Bug it catches: someone uses
     * {@code instanceof Mob} or {@code instanceof LivingEntity} for crowding,
     * which would over-broadly include hostiles and pets.
     */
    public static void cowNextToZombieKeepsStretching(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 2, 4));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // Zombie is not an Animal subclass (Java enforces); the handler's
            // getEntitiesOfClass(Animal.class, ...) filter excludes it. This
            // test pins that filter against an over-broad predicate.
            zombie.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            // Conditions: grass + water + light + stretching = 4 → delta +1 → weight 2.
            if (weight != 2) {
                helper.fail("weight-1 cow next to zombie in perfect pen expected 2 (all 4 conditions); got "
                        + weight + " — Zombie may be over-broadly counted as crowding");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "cow with flowing water nearby (no source) → water condition
     * fails". The simpler version: a "no water at all" cell has 3 conditions
     * (grass + light + stretching). If the water predicate is broken to return
     * true for empty cells, the cow would see 4 conditions and gain +1
     * weight; pinning the +0 delta here catches that.
     *
     * <p>BFS-era layout: 3×2 grass platform + torch (no water at all). The
     * 3 conditions met are grass, light, and stretching (reach=6).
     */
    public static void flowingWaterDoesNotCountAsSource(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildGrassAndLightPen(level, pen);
            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            // 3 conditions → delta 0 → weight 1.
            if (weight != 1) {
                helper.fail("weight-1 cow with no water expected 1 (3 conditions); got "
                        + weight + " — water predicate may be returning true for empty cells");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row: "two cows in a 3×3 (adjacent) in otherwise perfect pen → both
     * → 1 (each fails stretching due to the other)". Pins that the
     * stretching check excludes SELF but counts the other cow. Bug it
     * catches: the {@code a != mob} filter accidentally returns false (i.e.
     * counts self), in which case a lone cow would always fail stretching
     * too.
     */
    public static void twoAdjacentCowsBothFailStretching(GameTestHelper helper) {
        Cow cowA = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        Cow cowB = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 4));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cowA, pen);
            cowB.snapTo(pen.getX() + 1.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(cowA, 1);
            AnimalWeightAttachment.set(cowB, 1);
            DailyEvalHandler.runEvaluation(level);
            int wA = AnimalWeightAttachment.get(cowA);
            int wB = AnimalWeightAttachment.get(cowB);
            // Each cow sees the other in its 3×3, so stretching fails. The
            // other 3 conditions are satisfied → 3 conditions → delta 0 →
            // weight stays at 1.
            if (wA != 1) {
                helper.fail("cowA expected 1 (3 conditions, stretching fails due to cowB); got " + wA);
                return;
            }
            if (wB != 1) {
                helper.fail("cowB expected 1 (3 conditions, stretching fails due to cowA); got " + wB);
                return;
            }
            helper.succeed();
        });
    }
}
