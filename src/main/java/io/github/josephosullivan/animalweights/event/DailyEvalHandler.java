package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
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
     * <p>Target species (post run-003): Cow, Pig, Sheep, Chicken, Rabbit, and
     * Mooshroom. Mooshroom (vanilla {@code MushroomCow}) is auto-covered via
     * the {@link AbstractCow} check because in MC 26.1 both {@code Cow} and
     * {@code MushroomCow} extend {@code AbstractCow} (they are siblings, not
     * parent/child as in pre-26.1 vanilla).
     *
     * <p>Exposed for tests + manual triggering. Callers are responsible for
     * gating on dimension / time-of-day; this method just iterates and
     * applies.
     */
    public static void runEvaluation(ServerLevel level) {
        // Pulls every loaded target species across the entire level. AbstractCow
        // covers both vanilla Cow and MushroomCow (they share the abstract
        // parent in MC 26.1; MushroomCow does NOT extend Cow).
        List<? extends Animal> targets = level.getEntities(
                EntityTypeTest.forClass(Animal.class),
                entity -> entity instanceof AbstractCow || entity instanceof Pig
                        || entity instanceof Sheep || entity instanceof Chicken
                        || entity instanceof Rabbit);

        for (Animal mob : targets) {
            if (mob.isBaby()) {
                continue; // babies don't carry weight gameplay; transient (20 min)
            }
            int conditions = countConditionsMet(level, mob);
            int delta = AnimalWeightsTuning.DELTA_BY_CONDITIONS[conditions];
            if (delta == 0) {
                continue; // no-op; skip the attachment write
            }
            int current = AnimalWeightAttachment.get(mob);
            AnimalWeightAttachment.set(mob, current + delta);
        }
    }

    /**
     * Returns the count (0–4) of satisfied conditions for {@code mob}. This is
     * the index into {@link AnimalWeightsTuning#DELTA_BY_CONDITIONS}.
     *
     * <p>Package-private for future test access without exposing
     * {@link #runEvaluation} internals.
     */
    static int countConditionsMet(ServerLevel level, Animal mob) {
        Set<BlockPos> reachable = computeReachable(level, mob);
        int count = 0;
        if (hasReachableLight(level, reachable)) count++;
        if (hasReachableWaterSource(level, reachable)) count++;
        if (hasReachableGrazingSurface(level, reachable)) count++;
        if (reachable.size() >= AnimalWeightsTuning.MIN_ROAMING_CELLS) count++;
        return count;
    }

    /**
     * BFS from the mob's standing block over horizontally-adjacent cells the
     * mob could walk to, treating fences / walls / solid blocks as obstacles
     * and other farm animals as blockers. Allows 1-block step up or down per
     * move to handle stairs and uneven pens. Bounded by
     * {@link AnimalWeightsTuning#ROAMING_MAX_BFS_CELLS} (compute cap) and by
     * {@link AnimalWeightsTuning#EVAL_RADIUS_BLOCKS} (max chess-distance from
     * the starting cell).
     *
     * <p>Subsumes the old "8-block AABB scan" approach for every condition —
     * a cow in a fenced pen can no longer count water sources on the far side
     * of the fence, matching the original Reddit concept's "can't pathfind to
     * light or water" wording.
     */
    static Set<BlockPos> computeReachable(ServerLevel level, Animal mob) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = mob.blockPosition();
        visited.add(start);
        queue.add(start);
        int maxCells = AnimalWeightsTuning.ROAMING_MAX_BFS_CELLS;
        int maxDist = AnimalWeightsTuning.EVAL_RADIUS_BLOCKS;
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
                    if (!isWalkableCell(level, next, mob)) {
                        continue;
                    }
                    visited.add(next);
                    queue.add(next);
                    if (visited.size() >= maxCells) {
                        return visited;
                    }
                    break;
                }
            }
        }
        return visited;
    }

    private static final int[] DELTA_Y = { 0, -1, 1 };

    /**
     * A cell is walkable if it has a FULL-CUBE floor (excludes fence tops,
     * stairs, slabs — none of which a vanilla mob can actually stand on
     * cleanly), the cell itself + one above are clear of collision, and no
     * OTHER farm animal currently occupies it. Self-occupation is ignored so
     * the BFS starts validly at the mob's own position.
     *
     * <p>The full-cube check is the load-bearing constraint here: without it
     * the BFS happily strolls along the top of pen fences because the cell
     * above a fence post is just air (empty collision) while the fence below
     * has a non-empty collision shape. Real mobs can't stand there — the
     * fence's collision extends 0.5 above its base — but the BFS doesn't
     * model partial-block intrusion.
     */
    private static boolean isWalkableCell(ServerLevel level, BlockPos pos, Animal self) {
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
        AABB cellBox = new AABB(pos);
        List<Animal> others = level.getEntitiesOfClass(Animal.class, cellBox,
                a -> a != self && (a instanceof AbstractCow || a instanceof Pig
                        || a instanceof Sheep || a instanceof Chicken
                        || a instanceof Rabbit));
        return others.isEmpty();
    }

    private static boolean hasReachableLight(ServerLevel level, Set<BlockPos> reachable) {
        for (BlockPos pos : reachable) {
            if (level.getMaxLocalRawBrightness(pos) >= AnimalWeightsTuning.LIGHT_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Water source nearby" — path-restricted: a water source block OR a
     * filled water cauldron ({@code Blocks.WATER_CAULDRON}, which covers
     * both bucket-filled and rain-filled) must sit as a horizontal neighbour
     * of at least one cell in the mob's reachable set, at the same Y level
     * OR one block below ("sunken trough" / pond carved one block deep into
     * the ground — the mob leans down to drink).
     *
     * <p>The reachability requirement means a cauldron inside the pen counts
     * (its neighbouring grass cells are reachable, the cauldron is their
     * neighbour) but the ocean on the other side of a fence does not (the
     * mob can't BFS past the fence so no reachable cell is adjacent to the
     * water beyond it).
     */
    private static boolean hasReachableWaterSource(ServerLevel level, Set<BlockPos> reachable) {
        for (BlockPos pos : reachable) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(dir);
                if (isWaterOrFilledCauldron(level, neighbour)) {
                    return true;
                }
                if (isWaterOrFilledCauldron(level, neighbour.below())) {
                    return true;
                }
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

    private static boolean hasReachableGrazingSurface(ServerLevel level, Set<BlockPos> reachable) {
        for (BlockPos pos : reachable) {
            var below = level.getBlockState(pos.below()).getBlock();
            if (below == Blocks.GRASS_BLOCK || below == Blocks.MOSS_BLOCK) {
                return true;
            }
        }
        return false;
    }

}
