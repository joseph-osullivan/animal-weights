package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.event.DailyEvalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bodies for Tier-2 GameTests covering the BFS-reachability replacement for the
 * old AABB scan. Spec source: run-004 {@code design.md} sections A + B.
 *
 * <p>The handler under test is {@link DailyEvalHandler#computeReachable},
 * indirectly exercised via {@link DailyEvalHandler#runEvaluation}. The BFS
 * starts from the mob's standing block and walks horizontally-adjacent
 * walkable cells (full-cube floor, clear air, no farm-animal occupant). All
 * four conditions are then evaluated against the reachable set:
 * <ul>
 *   <li>light: any cell has raw brightness &ge; {@code LIGHT_THRESHOLD}</li>
 *   <li>water: any cell has a horizontal-neighbour that is water source or
 *       water cauldron (at the same Y or Y-1)</li>
 *   <li>grazing: any cell has grass or moss below</li>
 *   <li>stretching: reach size &ge; {@code MIN_ROAMING_CELLS = 6}</li>
 * </ul>
 *
 * <p>Section A pins that fences and other farm animals correctly bound the
 * reachable set. Section B pins the threshold of exactly 6 cells.
 *
 * <p>Each test isolates its world location via {@link #nextIsolatedPos()} —
 * same approach as {@link DailyEvalGameTests}. The grid-wide eval pass would
 * otherwise see neighbouring test cells' fixtures (the eval mutates every
 * loaded farm animal).
 */
public final class BfsReachabilityGameTests {

    private BfsReachabilityGameTests() {}

    /**
     * Counter assigning each test invocation a unique X offset for its
     * isolated world location. Uses a separate base from
     * {@link DailyEvalGameTests} so the two classes' tests don't collide.
     */
    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);

    private static final int ISOLATION_Y = 128;
    // Far enough from cow (100k), chicken (200k), rabbit (300k), and mooshroom
    // (400k) test ranges that BFS scans never overlap with other tests' fixtures.
    private static final int ISOLATION_BASE_X = 500_000;
    private static final int ISOLATION_STRIDE = 1000;

    private static BlockPos nextIsolatedPos() {
        int n = ISOLATION_X.getAndIncrement();
        return new BlockPos(ISOLATION_BASE_X + n * ISOLATION_STRIDE, ISOLATION_Y, 0);
    }

    /** Convenience: set a block at an absolute world position with default flags. */
    private static void setAbsBlock(ServerLevel level, BlockPos abs, BlockState state) {
        level.setBlock(abs, state, 3);
    }

    /** Teleport entity to absolute pos (cow stands ON center, so y is its standing y). */
    private static void teleportToIsolation(Cow cow, BlockPos abs) {
        cow.snapTo(abs.getX() + 0.5, (double) abs.getY(), abs.getZ() + 0.5, 0.0F, 0.0F);
    }

    // ----------------------------------------------------------------------
    // Section A — BFS reachability bounds
    // ----------------------------------------------------------------------

    /**
     * Spec row A: "Cow alone in a 2×2 fenced pen on a grass island surrounded
     * by ocean. Should fail water (BFS can't reach past fence) AND stretching
     * (reach &lt; 6). {@code met=2 → delta=-1}."
     *
     * <p>Pins the whole "fence-isolated cow can't count outside resources"
     * fix. Without the BFS bounds, the cow would AABB-scan the surrounding
     * ocean and count water → +1 condition → wrong delta.
     *
     * <p>Layout: 2×2 grass at Y-1 (center, +1) × (0, +1). 4 fence posts at
     * Y-1 forming the perimeter (positions (-1,_,0..1), (2,_,0..1),
     * (0..1,_,-1), (0..1,_,2)). Outside the fence: water sources at Y-1
     * forming "ocean". Reach = 4 (only the 4 interior cells walkable). Met:
     * grass + light (sky=15) = 2. Water blocked by fence. Stretching blocked
     * by reach &lt; 6. Cow at starting weight 2 → expect 1 after eval (-1 delta).
     */
    public static void cowInFencedTwoByTwoPenLosesWeight(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // 2x2 grass interior at Y-1, cells (0..1)x(0..1).
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    setAbsBlock(level, pen.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            // Fence perimeter at Y-1: 8 cells around the 2x2 interior.
            // Walls of the 4x4 outer rectangle minus the 2x2 inner cells.
            BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
            for (int dz = 0; dz <= 1; dz++) {
                setAbsBlock(level, pen.offset(-1, -1, dz), fence);
                setAbsBlock(level, pen.offset(2, -1, dz), fence);
            }
            for (int dx = -1; dx <= 2; dx++) {
                setAbsBlock(level, pen.offset(dx, -1, -1), fence);
                setAbsBlock(level, pen.offset(dx, -1, 2), fence);
            }
            // "Ocean" outside the fence: water sources at Y-1 around the
            // perimeter, one block beyond each fence cell. The fence blocks
            // BFS so these MUST NOT register as water — that is the point of
            // this test.
            BlockState waterSource = Blocks.WATER.defaultBlockState();
            for (int dz = 0; dz <= 1; dz++) {
                setAbsBlock(level, pen.offset(-2, -1, dz), waterSource);
                setAbsBlock(level, pen.offset(3, -1, dz), waterSource);
            }
            for (int dx = -1; dx <= 2; dx++) {
                setAbsBlock(level, pen.offset(dx, -1, -2), waterSource);
                setAbsBlock(level, pen.offset(dx, -1, 3), waterSource);
            }

            AnimalWeightAttachment.set(cow, 2);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 1) {
                helper.fail("weight-2 cow in 2x2 fenced pen with ocean outside expected 1 (met=2, delta=-1); got "
                        + weight + " — BFS may be reaching past fence to count outside water, "
                        + "or stretching threshold is wrong");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row A: "Cow on top of fence cannot reach outside." Regression test
     * for the fence-walking bug — the BFS must not traverse cells whose floor
     * is a fence (fences are not {@code isCollisionShapeFullBlock}).
     *
     * <p>Layout: 3×3 grass platform at Y-1, perimeter fence at Y-1 around it.
     * A water source placed 2 blocks east of the east fence at Y-1. If the
     * BFS were buggy and walked the fence-top, it could reach the water; the
     * full-block floor check should prevent that.
     *
     * <p>Reach = 9 (interior 3×3 grass cells) → stretching passes. Met: grass
     * + light + stretching = 3 → delta 0 → starting weight 1 stays at 1. If
     * the bug existed, water would count → 4 met → delta +1 → weight 2.
     */
    public static void cowOnTopOfFenceCannotReachOutside(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // 3x3 grass interior at Y-1.
            for (int dx = 0; dx <= 2; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    setAbsBlock(level, pen.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            // Fence perimeter at Y-1 (cow's Y level so the fence is one block
            // tall above the surrounding "ground"). Place fence at the cells
            // one block outside the grass perimeter, also at Y-1 (the fence
            // top is then at Y, same plane as the cow stands at).
            BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
            for (int dz = -1; dz <= 3; dz++) {
                setAbsBlock(level, pen.offset(-1, -1, dz), fence);
                setAbsBlock(level, pen.offset(3, -1, dz), fence);
            }
            for (int dx = 0; dx <= 2; dx++) {
                setAbsBlock(level, pen.offset(dx, -1, -1), fence);
                setAbsBlock(level, pen.offset(dx, -1, 3), fence);
            }
            // Beyond the east fence, place a grass floor + water source 2
            // blocks out. If BFS walked the fence top it could reach this.
            setAbsBlock(level, pen.offset(5, -1, 1), Blocks.WATER.defaultBlockState());

            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 1) {
                helper.fail("cow in 3x3 fenced pen with water outside expected 1 (3 conditions met, "
                        + "delta 0); got " + weight + " — BFS may be walking the fence top to count "
                        + "outside water, or some other condition is wrong");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row A: "Cow blocked by other animals has smaller reach." Pins that
     * an OTHER farm animal in a neighbouring cell blocks BFS expansion.
     *
     * <p>Layout: 5×5 grass platform at Y-1 (would normally give reach=25,
     * stretching passes easily). 4 cow neighbours placed at the 4 cardinal
     * cells around the target cow. Target's BFS:
     * <ul>
     *   <li>start cell (target's position): visited</li>
     *   <li>N/E/S/W neighbour cells: each occupied by a blocker cow →
     *       {@code isWalkableCell} rejects via the
     *       {@code others.isEmpty()} check</li>
     * </ul>
     * Reach = 1 → stretching fails. With grass + light still satisfied →
     * 2 conditions → delta -1. Target weight 2 → 1.
     *
     * <p>Note: the BFS uses {@code break} after the first walkable dy match,
     * but rejects the cell entirely if no dy works. Since all three dy values
     * (0, -1, +1) for the neighbouring N/E/S/W cells have the blocker cow at
     * Y=0 within their AABB, every dy candidate fails.
     */
    public static void cowBlockedByOtherAnimalHasSmallerReach(GameTestHelper helper) {
        // Spawn blockers at distinct test-cell-relative positions so they
        // don't overlap before the snapTo. Overlapping entity spawn risks
        // post-spawn collision push that could mis-position blockers.
        Cow target = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        Cow blockerN = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 1));
        Cow blockerE = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 3));
        Cow blockerS = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 5));
        Cow blockerW = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(1, 2, 3));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            // 5x5 grass at Y-1 so BFS would normally have reach >= 6 (no
            // crowding). Place BEFORE snapping the cows in so they land on
            // grass cleanly.
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    setAbsBlock(level, pen.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            teleportToIsolation(target, pen);
            // Place blockers at the 4 cardinal cells one block from target.
            // Target stands at pen (cell offset (0,0,0)). North = (0,_,-1),
            // East = (1,_,0), South = (0,_,1), West = (-1,_,0).
            blockerN.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() - 1 + 0.5, 0.0F, 0.0F);
            blockerE.snapTo(pen.getX() + 1 + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            blockerS.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 1 + 0.5, 0.0F, 0.0F);
            blockerW.snapTo(pen.getX() - 1 + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);

            // Poll the eval rather than checking once at a fixed delay. The
            // level's entity chunk index doesn't always reflect the snapTo'd
            // positions on the very next tick — `getEntitiesOfClass(cellBox)`
            // in DailyEvalHandler may miss the blockers for a few ticks until
            // the index catches up. succeedWhen retries every tick until the
            // assertion holds or the test's max_ticks budget runs out.
            //
            // Each iteration is idempotent: reset target+blocker weights to 2,
            // call runEvaluation, check if the target dropped to 1. Once the
            // index has caught up, the eval correctly sees reach=1 and the
            // delta -1 lands.
            helper.succeedWhen(() -> {
                AnimalWeightAttachment.set(target, 2);
                AnimalWeightAttachment.set(blockerN, 2);
                AnimalWeightAttachment.set(blockerE, 2);
                AnimalWeightAttachment.set(blockerS, 2);
                AnimalWeightAttachment.set(blockerW, 2);
                DailyEvalHandler.runEvaluation(level);
                int weight = AnimalWeightAttachment.get(target);
                if (weight != 1) {
                    throw new net.minecraft.gametest.framework.GameTestAssertException(
                            net.minecraft.network.chat.Component.literal(
                                    "target cow surrounded by 4 blocker cows expected weight 1 "
                                            + "(reach=1, met=2 [grass+light], delta=-1); got " + weight),
                            0);
                }
            });
        });
    }

    // ----------------------------------------------------------------------
    // Section B — MIN_ROAMING_CELLS threshold (6)
    // ----------------------------------------------------------------------

    /**
     * Spec row B: "1×6 walkable corridor → reach &ge; 6 → stretching=true."
     * Boundary check at exactly the threshold.
     *
     * <p>Layout: 6 stone blocks in a 1×6 line at Y-1. Cow stands on one end.
     * BFS expands east 5 times → reach=6. Stone (not grass), no water, but
     * light=yes (sky), stretching=yes → 2 met → delta -1. Starting weight 2 →
     * 1.
     *
     * <p>Bug it catches: an off-by-one in MIN_ROAMING_CELLS check (e.g. using
     * {@code &gt;} instead of {@code &ge;}) would fail stretching at exactly 6.
     */
    public static void cowWithExactlySixReachableCellsPassesStretching(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // 1x6 stone corridor at Y-1.
            for (int dx = 0; dx <= 5; dx++) {
                setAbsBlock(level, pen.offset(dx, -1, 0), Blocks.STONE.defaultBlockState());
            }
            AnimalWeightAttachment.set(cow, 2);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            // Expect: stretching=yes (reach=6), light=yes (sky), grass=no
            // (stone), water=no → 2 met → delta -1.
            if (weight != 1) {
                helper.fail("cow on 1x6 stone corridor expected 1 (reach=6 passes stretching, met=2, "
                        + "delta=-1); got " + weight
                        + " — stretching threshold may be > 6 instead of >= 6, or BFS missed a cell");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row B: "1×5 walkable corridor → reach=5 &lt; 6 → stretching=false."
     * Boundary check just below the threshold.
     *
     * <p>Layout: 5 stone blocks in a 1×5 line at Y-1. Reach=5. Light=yes,
     * grass=no, water=no, stretching=no → 1 met → delta -2. Starting weight
     * 3 → 1 (clamped via {@code [0,8]} after the delta).
     *
     * <p>Bug it catches: an off-by-one in the other direction (using
     * {@code &gt;= 5} instead of {@code &gt;= 6}). With that bug, reach=5 would
     * incorrectly pass and the cow would gain a condition.
     */
    public static void cowWithFiveReachableCellsFailsStretching(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // 1x5 stone corridor at Y-1.
            for (int dx = 0; dx <= 4; dx++) {
                setAbsBlock(level, pen.offset(dx, -1, 0), Blocks.STONE.defaultBlockState());
            }
            AnimalWeightAttachment.set(cow, 3);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            // Expect: stretching=no (reach=5 < 6), light=yes (sky), grass=no,
            // water=no → 1 met → delta -2. 3 - 2 = 1.
            if (weight != 1) {
                helper.fail("cow on 1x5 stone corridor expected 1 (reach=5 fails stretching, met=1, "
                        + "delta=-2); got " + weight
                        + " — stretching threshold may be 5 instead of 6 (off-by-one)");
                return;
            }
            helper.succeed();
        });
    }
}
