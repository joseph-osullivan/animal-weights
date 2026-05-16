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
 * Bodies for Tier-2 GameTests covering water condition variants. Spec source:
 * run-004 {@code design.md} sections F (water cauldron) + G (reachable-only
 * water — no fence cheat).
 *
 * <p>Section F pins the cauldron paths:
 * <ul>
 *   <li>filled WATER_CAULDRON at the same Y as a reachable cell's horizontal
 *       neighbour → water=true</li>
 *   <li>empty CAULDRON at the same position → water=false (the production
 *       check is specifically {@code Blocks.WATER_CAULDRON}, not the empty
 *       cauldron block)</li>
 *   <li>WATER_CAULDRON at Y-1 of a reachable cell's neighbour (sunken trough)
 *       → water=true</li>
 * </ul>
 *
 * <p>Section G pins the fence-blocks-water symmetry:
 * <ul>
 *   <li>water source 1 block beyond a fence → water=false (BFS can't reach)</li>
 *   <li>same water source inside the pen → water=true</li>
 * </ul>
 *
 * <p>Each test uses an isolated world position to avoid the
 * {@code runEvaluation}'s level-wide iteration mutating other tests' cows.
 *
 * <p>Common layout for tests that need 3 of 4 conditions independently
 * satisfied (so water is the only variable): a 3×2 grass platform at Y-1.
 * Reach = 6 → stretching passes (== {@code MIN_ROAMING_CELLS}). Grass below
 * → grass passes. Sky light at the isolated Y=128 → light passes. Then we
 * place water in different configurations to flip the 4th condition.
 */
public final class WaterCauldronGameTests {

    private WaterCauldronGameTests() {}

    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);
    private static final int ISOLATION_Y = 128;
    // Far enough from cow/chicken/rabbit/mooshroom/bfs/baby test ranges.
    private static final int ISOLATION_BASE_X = 700_000;
    private static final int ISOLATION_STRIDE = 1000;

    private static BlockPos nextIsolatedPos() {
        int n = ISOLATION_X.getAndIncrement();
        return new BlockPos(ISOLATION_BASE_X + n * ISOLATION_STRIDE, ISOLATION_Y, 0);
    }

    private static void setAbsBlock(ServerLevel level, BlockPos abs, BlockState state) {
        level.setBlock(abs, state, 3);
    }

    private static void teleportToIsolation(Cow cow, BlockPos abs) {
        cow.snapTo(abs.getX() + 0.5, (double) abs.getY(), abs.getZ() + 0.5, 0.0F, 0.0F);
    }

    /**
     * Build a 3×2 grass platform at Y-1, giving reach=6 (stretching passes)
     * and grass=yes. Light=yes via sky exposure. Water configuration is set
     * separately by each test.
     */
    private static void buildGrassPlatformBase(ServerLevel level, BlockPos center) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                setAbsBlock(level, center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
    }

    // ----------------------------------------------------------------------
    // Section F — Water cauldron variants
    // ----------------------------------------------------------------------

    /**
     * Spec row F: "{@code Blocks.WATER_CAULDRON} inside the pen counts as
     * water." Place a filled water cauldron at the horizontal neighbour cell
     * of a reachable cell, at the SAME Y as the cow (cow stands at Y, the
     * cauldron block occupies Y of the neighbouring column).
     *
     * <p>Reach=6, grass+light+stretching satisfied, water=yes via cauldron →
     * 4 met → +1 delta. Weight 1 → 2.
     */
    public static void cauldronInsidePenCountsAsWater(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildGrassPlatformBase(level, pen);
            // Water cauldron at the cow's east edge, same Y as cow.
            // Reach cells include (2, 0, 0) and (2, 0, 1); their east
            // neighbour is (3, 0, *). Place cauldron at (3, 0, 0).
            setAbsBlock(level, pen.offset(3, 0, 0), Blocks.WATER_CAULDRON.defaultBlockState());

            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 2) {
                helper.fail("cow next to WATER_CAULDRON (same Y) expected weight 2 (4 conditions met, "
                        + "delta +1); got " + weight + " — cauldron-as-water predicate may be missing "
                        + "for the same-Y path");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row F: "Empty CAULDRON (not filled) does NOT count as water."
     *
     * <p>Same layout as the filled-cauldron test, but uses {@code Blocks.CAULDRON}
     * instead of {@code Blocks.WATER_CAULDRON}. The production check is
     * specifically {@code blockState.is(Blocks.WATER_CAULDRON)} so an empty
     * cauldron should not match.
     *
     * <p>Met: grass+light+stretching = 3, water=no → delta 0 → weight stays at 1.
     */
    public static void emptyCauldronDoesNotCount(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildGrassPlatformBase(level, pen);
            // Empty cauldron in the same position as the filled-cauldron test.
            setAbsBlock(level, pen.offset(3, 0, 0), Blocks.CAULDRON.defaultBlockState());

            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 1) {
                helper.fail("cow next to empty CAULDRON expected weight 1 (3 conditions met, "
                        + "delta 0); got " + weight + " — empty cauldron may be incorrectly counted "
                        + "as water (production should check WATER_CAULDRON specifically)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row F: "Sunken cauldron at Y-1 of a neighbouring cell counts as
     * water." The production code checks both {@code neighbour} and
     * {@code neighbour.below()} for each reachable cell's horizontal neighbours.
     *
     * <p>Layout: 3×2 grass platform at Y-1. Water cauldron at
     * {@code (3, Y-1, 0)} (east of the east edge of the pen, at the GRASS Y).
     * From the cow's reach cell (2, Y, 0), east neighbour is (3, Y, 0) — air.
     * The check then looks at (3, Y-1, 0) — the cauldron. Match → water=yes.
     *
     * <p>Met: 4 → delta +1 → weight 1 → 2.
     */
    public static void sunkenCauldronYMinusOneCounts(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            buildGrassPlatformBase(level, pen);
            // Sunken cauldron at Y-1 of the east neighbour column.
            setAbsBlock(level, pen.offset(3, -1, 0), Blocks.WATER_CAULDRON.defaultBlockState());

            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 2) {
                helper.fail("cow next to sunken WATER_CAULDRON (Y-1) expected weight 2 (4 conditions, "
                        + "delta +1); got " + weight + " — the Y-1 cauldron-as-water path may be "
                        + "missing from hasReachableWaterSource");
                return;
            }
            helper.succeed();
        });
    }

    // ----------------------------------------------------------------------
    // Section G — Reachable-only water (no fence cheat)
    // ----------------------------------------------------------------------

    /**
     * Spec row G: "Water source one block outside the fence does NOT count."
     * Pins that the BFS-bounded water check actually requires reachable
     * proximity, not just spatial proximity.
     *
     * <p>Layout: 3×3 grass platform at Y-1, fence perimeter at the
     * outer ring at Y-1 (same height as the grass), and a water source at
     * {@code (4, Y-1, 1)} — one block east of the east fence at the grass Y.
     * Cow at center (1, Y, 1). Reach = 9 (3×3 interior). Stretching passes.
     * Light + grass also pass. Water = no (BFS doesn't cross fence → the
     * water source is never a neighbour of any reachable cell).
     *
     * <p>3 met → delta 0 → weight 1 stays at 1. Bug it catches: a BFS that
     * accidentally counts blocks outside the reachable set's neighbour ring,
     * e.g. via an AABB scan fallback.
     */
    public static void waterSourceAcrossFenceDoesNotCount(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // 3x3 grass at Y-1.
            for (int dx = 0; dx <= 2; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    setAbsBlock(level, pen.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            // Fence perimeter at Y-1.
            BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
            for (int dz = -1; dz <= 3; dz++) {
                setAbsBlock(level, pen.offset(-1, -1, dz), fence);
                setAbsBlock(level, pen.offset(3, -1, dz), fence);
            }
            for (int dx = 0; dx <= 2; dx++) {
                setAbsBlock(level, pen.offset(dx, -1, -1), fence);
                setAbsBlock(level, pen.offset(dx, -1, 3), fence);
            }
            // Water source 1 block east of east fence, at grass Y-1.
            setAbsBlock(level, pen.offset(4, -1, 1), Blocks.WATER.defaultBlockState());
            // Teleport cow to interior center (1, Y, 1) of the pen.
            cow.snapTo(pen.getX() + 1 + 0.5, (double) pen.getY(), pen.getZ() + 1 + 0.5, 0.0F, 0.0F);

            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 1) {
                helper.fail("cow in fenced 3x3 pen with water source outside the fence expected weight "
                        + "1 (3 met, delta 0); got " + weight + " — water predicate may be using a "
                        + "raw AABB scan instead of the reachable-neighbour check");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row G: "Water source inside the pen counts." Companion to the
     * across-fence test; pins that the SAME fence layout DOES register water
     * when the source is inside the BFS reach.
     *
     * <p>Layout: identical 3×3 fenced pen, but the water source replaces a
     * grass cell at the east edge of the interior. Specifically, replace the
     * grass at {@code (2, Y-1, 1)} with a water source. That cell is no
     * longer walkable (water isn't {@code isCollisionShapeFullBlock}), so
     * reach drops to 8. Still &ge; 6 → stretching passes. The remaining 8
     * cells include cells horizontally-adjacent to (2, Y, 1) which has a
     * Y-1 water source → water=yes.
     *
     * <p>Met = 4 → delta +1 → weight 1 → 2.
     */
    public static void waterSourceInsidePenCounts(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            teleportToIsolation(cow, pen);
            // 3x3 grass at Y-1, with one cell replaced by water source.
            for (int dx = 0; dx <= 2; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    setAbsBlock(level, pen.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            setAbsBlock(level, pen.offset(2, -1, 1), Blocks.WATER.defaultBlockState());
            // Fence perimeter at Y-1.
            BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
            for (int dz = -1; dz <= 3; dz++) {
                setAbsBlock(level, pen.offset(-1, -1, dz), fence);
                setAbsBlock(level, pen.offset(3, -1, dz), fence);
            }
            for (int dx = 0; dx <= 2; dx++) {
                setAbsBlock(level, pen.offset(dx, -1, -1), fence);
                setAbsBlock(level, pen.offset(dx, -1, 3), fence);
            }
            // Teleport cow to interior west of the water source.
            cow.snapTo(pen.getX() + 0 + 0.5, (double) pen.getY(), pen.getZ() + 1 + 0.5, 0.0F, 0.0F);

            AnimalWeightAttachment.set(cow, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(cow);
            if (weight != 2) {
                helper.fail("cow in fenced 3x3 pen with INSIDE water source expected weight 2 (4 met, "
                        + "delta +1); got " + weight + " — water predicate may not see source inside "
                        + "the pen, or BFS expansion didn't include cells adjacent to the source");
                return;
            }
            helper.succeed();
        });
    }
}
