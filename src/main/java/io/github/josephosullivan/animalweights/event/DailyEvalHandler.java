package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.AnimalWeightsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Fires the daily condition evaluation that drives weight changes for
 * Cow/Pig/Sheep on the Overworld.
 *
 * <p>The handler subscribes to {@link LevelTickEvent.Post}. Every tick it
 * checks whether the level being ticked is the Overworld and whether
 * {@code getDayTime() % 24000 == 0} (i.e. we just crossed a dawn boundary).
 * A per-{@link ServerLevel} dedupe map (last-fired day index) prevents
 * firing twice if more than one boundary tick lands on the same in-game day —
 * e.g. when the day cycle is paused or replayed.
 *
 * <p>The per-mob evaluation lives in {@link #runEvaluation(ServerLevel)} so
 * Tier-2 GameTests (and a future {@code /animalweights eval} debug command)
 * can trigger it directly without manipulating in-game time.
 *
 * <p>Self-registers via {@link EventBusSubscriber}; the orchestrator does
 * not need to wire it up in {@link AnimalWeights}. Restricted to the
 * dedicated-server dist because the dawn tick is server-authoritative; the
 * client never needs to run it.
 */
@EventBusSubscriber(modid = AnimalWeights.MOD_ID)
public final class DailyEvalHandler {

    private DailyEvalHandler() {
        // event-handler entry points are static; no instances
    }

    /** Length of one Minecraft day in ticks. */
    private static final long TICKS_PER_DAY = 24000L;

    /**
     * Per-{@link ServerLevel} dedupe state: last day index (game time / 24000)
     * for which we already fired the dawn evaluation. Using a
     * {@link WeakHashMap} keyed by the level instance means an unloaded
     * dimension's entry GCs automatically; no leak.
     *
     * <p>Access is single-threaded — the level-tick event runs on the
     * server thread for that level, and we never touch this map from
     * another level's tick or another thread. No synchronization needed.
     */
    private static final Map<ServerLevel, Long> LAST_FIRED_DAY = new WeakHashMap<>();

    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Suppress auto-trigger when running under a dedicated GameTest server.
        // GameTests call runEvaluation(level) directly to control timing
        // deterministically; the auto path firing in parallel introduced races
        // that flaked slowness + reachability tests (~20-40%).
        if (level.getServer() instanceof GameTestServer) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        // MC 26.1 removed Level.getDayTime(); use the overworld clock time.
        // Trigger on day-index *change* rather than exact-tick modulo, so the
        // eval still fires when something skips time (sleeping past midnight,
        // /time add, command blocks). In natural play, day index advances
        // when the clock wraps from 23999 → 24000; with a time jump it can
        // skip multiple indices — we still fire once per advance.
        long dayIndex = level.getOverworldClockTime() / TICKS_PER_DAY;
        Long lastFired = LAST_FIRED_DAY.get(level);
        if (lastFired != null && lastFired == dayIndex) {
            return; // already evaluated this day
        }
        LAST_FIRED_DAY.put(level, dayIndex);
        runEvaluation(level);
    }

    /**
     * Evaluates every loaded target-species mob in {@code level} against the
     * four conditions in {@code design.md} and applies the resulting weight
     * delta.
     *
     * <p>Target species is the
     * {@link io.github.josephosullivan.animalweights.AnimalWeightsTags#TRACKED}
     * entity-type tag — by default cow, mooshroom, pig, sheep, chicken,
     * rabbit; extensible to modded animals via datapack contribution to the
     * same tag.
     *
     * <p>Exposed for tests + manual triggering. Callers are responsible for
     * gating on dimension / time-of-day; this method just iterates and
     * applies.
     */
    public static void runEvaluation(ServerLevel level) {
        // Pulls every loaded target species across the entire level. Membership
        // is the AnimalWeightsTags.TRACKED entity-type tag (default: cow,
        // mooshroom, pig, sheep, chicken, rabbit; extensible via datapack).
        List<? extends Animal> targets = level.getEntities(
                EntityTypeTest.forClass(Animal.class),
                AnimalWeightAttachment::isTracked);

        // Perf fix #1: pre-compute the occupant set once instead of running a
        // per-cell AABB entity scan inside the BFS. With N target animals the
        // old code did ~N * BFS_CELLS getEntitiesOfClass calls on the dawn
        // tick (each itself iterating chunk-section entity lists). Now it's
        // one O(N) sweep over `targets`, then O(1) Set lookups in the BFS.
        // Babies don't block cell walkability — they are evaluation-exempt and
        // mob-pathfinding treats them as soft obstacles only — so we exclude
        // them from `occupied`.
        Set<BlockPos> occupied = new HashSet<>();
        for (Animal a : targets) {
            if (!a.isBaby()) {
                occupied.add(a.blockPosition());
            }
        }

        // Re-read the delta table once per evaluation pass instead of per
        // mob — config reads cross a lock and TOML coercion shouldn't run on
        // the hot per-mob loop.
        int[] deltaTable = AnimalWeightsConfig.deltaByConditions();
        for (Animal mob : targets) {
            if (mob.isBaby()) {
                continue; // babies don't carry weight gameplay; transient (20 min)
            }
            int conditions = countConditionsMet(level, mob, occupied);
            int delta = deltaTable[conditions];
            if (delta == 0) {
                continue; // no-op; skip the attachment write
            }
            int current = AnimalWeightAttachment.get(mob);
            AnimalWeightAttachment.set(mob, current + delta);
        }
    }

    /**
     * Returns the count (0–4) of satisfied conditions for {@code mob}. This is
     * the index into the configured delta-by-conditions table (see
     * {@link AnimalWeightsConfig.Server#deltaByConditions}).
     *
     * <p>Package-private for future test access without exposing
     * {@link #runEvaluation} internals.
     */
    static int countConditionsMet(ServerLevel level, Animal mob, Set<BlockPos> occupied) {
        ReachabilityResult r = computeReachable(level, mob, occupied);
        int count = 0;
        if (r.lightOK) count++;
        if (r.waterOK) count++;
        if (r.grazingOK) count++;
        if (r.reachable.size() >= AnimalWeightsConfig.SERVER.minRoamingCells.get()) count++;
        return count;
    }

    /**
     * Result of {@link #computeReachable(ServerLevel, Animal, Set)} — the set
     * of reachable cells plus the three condition flags that were tested
     * inline during BFS expansion.
     */
    static record ReachabilityResult(
            Set<BlockPos> reachable,
            boolean lightOK,
            boolean waterOK,
            boolean grazingOK) {
    }

    /**
     * BFS from the mob's standing block over horizontally-adjacent cells the
     * mob could walk to, treating fences / walls / solid blocks as obstacles
     * and other farm animals as blockers. Allows 1-block step up or down per
     * move to handle stairs and uneven pens. Bounded by the configured
     * {@code roaming_max_bfs_cells} (compute cap) and {@code eval_radius_blocks}
     * (max chess-distance from the starting cell).
     *
     * <p>Subsumes the old "8-block AABB scan" approach for every condition —
     * a cow in a fenced pen can no longer count water sources on the far side
     * of the fence, matching the original Reddit concept's "can't pathfind to
     * light or water" wording.
     *
     * <p>Perf fix #4: the light / water-source / grazing-surface checks are
     * folded into BFS expansion. Each cell, as it's discovered, contributes
     * to the {@code lightOK / waterOK / grazingOK} flags. The BFS
     * short-circuits as soon as all three flags are set AND the reachable
     * set has reached the configured {@code min_roaming_cells} — at that
     * point all four conditions are guaranteed satisfied and further BFS
     * work would only refine numbers the caller never uses. Most well-kept
     * pens hit this early-out in &lt;10 cells.
     */
    static ReachabilityResult computeReachable(ServerLevel level, Animal mob, Set<BlockPos> occupied) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = mob.blockPosition();
        visited.add(start);
        queue.add(start);

        // Condition flags accumulated during BFS expansion. The start cell is
        // evaluated up front; every cell added to `visited` afterwards is
        // evaluated as it's added (see addCellChecks below).
        boolean lightOK = checkLight(level, start);
        boolean waterOK = checkWater(level, start);
        boolean grazingOK = checkGrazing(level, start);
        int minRoam = AnimalWeightsConfig.SERVER.minRoamingCells.get();
        if (lightOK && waterOK && grazingOK && visited.size() >= minRoam) {
            return new ReachabilityResult(visited, true, true, true);
        }

        int maxCells = AnimalWeightsConfig.SERVER.roamingMaxBfsCells.get();
        int maxDist = AnimalWeightsConfig.SERVER.evalRadius.get();
        while (!queue.isEmpty() && visited.size() < maxCells) {
            BlockPos cur = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos lateral = cur.relative(dir);
                // Commit to whichever of (same Y, Y-1, Y+1) is walkable first.
                // Mirrors vanilla single-step pathfinding semantics.
                for (int dy : DELTA_Y) {
                    BlockPos next = new BlockPos(lateral.getX(), lateral.getY() + dy, lateral.getZ());
                    if (visited.contains(next)) {
                        break;
                    }
                    if (Math.abs(next.getX() - start.getX()) > maxDist
                            || Math.abs(next.getZ() - start.getZ()) > maxDist
                            || Math.abs(next.getY() - start.getY()) > maxDist) {
                        continue;
                    }
                    if (!isWalkableCell(level, next, occupied)) {
                        continue;
                    }
                    visited.add(next);
                    queue.add(next);

                    // Fold post-BFS scans into expansion: evaluate the three
                    // conditions at this newly-discovered cell.
                    if (!lightOK && checkLight(level, next)) {
                        lightOK = true;
                    }
                    if (!waterOK && checkWater(level, next)) {
                        waterOK = true;
                    }
                    if (!grazingOK && checkGrazing(level, next)) {
                        grazingOK = true;
                    }
                    // Short-circuit: all three conditions satisfied AND we
                    // have enough roaming cells. The caller computes the
                    // final count from these flags + reachable.size().
                    if (lightOK && waterOK && grazingOK && visited.size() >= minRoam) {
                        return new ReachabilityResult(visited, true, true, true);
                    }
                    if (visited.size() >= maxCells) {
                        return new ReachabilityResult(visited, lightOK, waterOK, grazingOK);
                    }
                    break;
                }
            }
        }
        return new ReachabilityResult(visited, lightOK, waterOK, grazingOK);
    }

    private static final int[] DELTA_Y = { 0, -1, 1 };

    /**
     * A cell is walkable if it has a FULL-CUBE floor (excludes fence tops,
     * stairs, slabs — none of which a vanilla mob can actually stand on
     * cleanly), the cell itself + one above are clear of collision, and no
     * OTHER farm animal currently occupies it. Self-occupation is implicitly
     * handled by the BFS: the calling mob's start cell is seeded into
     * {@code visited} before any walkability check runs, so this method
     * never sees it.
     *
     * <p>Perf fix #1: occupant testing uses the pre-computed
     * {@code occupied} set rather than a per-cell {@code getEntitiesOfClass}
     * AABB scan. The set was built once at the top of
     * {@link #runEvaluation(ServerLevel)}.
     *
     * <p>The full-cube check is the load-bearing constraint here: without it
     * the BFS happily strolls along the top of pen fences because the cell
     * above a fence post is just air (empty collision) while the fence below
     * has a non-empty collision shape. Real mobs can't stand there — the
     * fence's collision extends 0.5 above its base — but the BFS doesn't
     * model partial-block intrusion.
     */
    private static boolean isWalkableCell(ServerLevel level, BlockPos pos, Set<BlockPos> occupied) {
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isCollisionShapeFullBlock(level, below)) {
            return false;
        }
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        BlockPos above = pos.above();
        if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
            return false;
        }
        return !occupied.contains(pos);
    }

    private static boolean checkLight(ServerLevel level, BlockPos pos) {
        return level.getMaxLocalRawBrightness(pos) >= AnimalWeightsConfig.SERVER.lightThreshold.get();
    }

    /**
     * "Water source nearby" predicate for a single cell — checks horizontal
     * neighbours at the same Y and one Y below for water source OR filled
     * water cauldron. Matches the semantics of the old
     * {@code hasReachableWaterSource} post-BFS scan, applied per cell so it
     * can be folded into BFS expansion.
     */
    private static boolean checkWater(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = pos.relative(dir);
            if (isWaterOrFilledCauldron(level, neighbour)) {
                return true;
            }
            if (isWaterOrFilledCauldron(level, neighbour.below())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWaterOrFilledCauldron(ServerLevel level, BlockPos pos) {
        var fluid = level.getFluidState(pos);
        if (fluid.is(Fluids.WATER) && fluid.isSource()) {
            return true;
        }
        return level.getBlockState(pos).is(Blocks.WATER_CAULDRON);
    }

    private static boolean checkGrazing(ServerLevel level, BlockPos pos) {
        var below = level.getBlockState(pos.below()).getBlock();
        return below == Blocks.GRASS_BLOCK || below == Blocks.MOSS_BLOCK;
    }

}
