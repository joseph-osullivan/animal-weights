package io.github.josephosullivan.animalweights.ai;

import io.github.josephosullivan.animalweights.AnimalWeightsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A {@link RandomStrollGoal} variant that prefers high-quality habitat when
 * picking the next stroll destination. The goal samples {@link #SAMPLES}
 * candidate positions via {@link LandRandomPos#getPos(PathfinderMob, int, int)}
 * (same generator vanilla strolls use, so we inherit pathability filtering)
 * and returns the candidate with the highest habitat score.
 *
 * <p><b>Scoring</b>: each candidate position is scored 0-3 by point checks
 * for the three condition flags driven by {@code DailyEvalHandler}:
 * <ul>
 *   <li>+1 if local block-light brightness at the candidate is at least
 *       the configured {@code light_threshold}.</li>
 *   <li>+1 if a water source (water fluid OR water-filled cauldron) is
 *       within {@link #WATER_SEARCH_RADIUS} blocks horizontally.</li>
 *   <li>+1 if the block below or any horizontal neighbour's below is grass
 *       or moss (a grazable surface).</li>
 * </ul>
 *
 * <p>The point-check approach is deliberately lighter than the full BFS
 * {@code DailyEvalHandler} runs at dawn — running a BFS per candidate (8 *
 * BFS cost) on every goal selection would dominate the goal-selector budget.
 * Goal direction-of-travel is a hint, not a verdict; the dawn eval is the
 * authoritative scorer.
 *
 * <p><b>Throttle</b>: {@link #canUse()} returns {@code false} for
 * {@link #RESELECT_COOLDOWN_TICKS} ticks after {@link #start()} fires. This
 * prevents habitat-seeking from re-firing every tick when the mob has just
 * been pointed at a destination.
 */
public class WanderToHabitatGoal extends RandomStrollGoal {

    /** Number of candidate positions sampled per habitat selection. */
    static final int SAMPLES = 8;

    /**
     * Cooldown (ticks) between selections. After {@link #start()} sets a
     * destination, {@link #canUse()} short-circuits to {@code false} for
     * this many ticks. Mirrors {@code RandomStrollGoal}'s
     * {@link RandomStrollGoal#DEFAULT_INTERVAL} 120-tick re-roll cadence but
     * applies a hard floor of 200 ticks (10s) so we are not paying the
     * 8-sample scoring cost on every roll.
     */
    static final int RESELECT_COOLDOWN_TICKS = 200;

    /**
     * Horizontal radius (blocks) searched for a water source when scoring a
     * candidate. Kept tight because we are doing this 8x per goal selection
     * and per-cell water lookups touch fluid + block state.
     */
    private static final int WATER_SEARCH_RADIUS = 4;

    /**
     * Forward / vertical search arguments passed to
     * {@link LandRandomPos#getPos(PathfinderMob, int, int)}. Match the
     * vanilla {@code RandomStrollGoal} defaults so candidate distribution is
     * recognisable to maintainers — only the *selection* differs.
     */
    private static final int CANDIDATE_HORIZONTAL = 10;
    private static final int CANDIDATE_VERTICAL = 7;

    /**
     * Last game-tick at which {@link #start()} fired, or {@code Long.MIN_VALUE}
     * if the goal has never been started. Used by {@link #canUse()} to enforce
     * {@link #RESELECT_COOLDOWN_TICKS}.
     */
    private long lastUseTick = Long.MIN_VALUE;

    public WanderToHabitatGoal(PathfinderMob mob, double speedModifier) {
        super(mob, speedModifier);
    }

    @Override
    public boolean canUse() {
        long now = this.mob.level().getGameTime();
        if (lastUseTick != Long.MIN_VALUE && now - lastUseTick < RESELECT_COOLDOWN_TICKS) {
            return false;
        }
        return super.canUse();
    }

    @Override
    public void start() {
        super.start();
        this.lastUseTick = this.mob.level().getGameTime();
    }

    /**
     * Samples {@link #SAMPLES} candidate positions and returns the one with
     * the highest habitat score. Falls back to {@code null} if no sample
     * generated a pathable target — same null contract as the superclass.
     *
     * <p>Ties on score are broken by the first sample seen (sample order is
     * the random generator's natural draw order), which is acceptable
     * randomness for a stroll goal.
     */
    @Override
    protected @Nullable Vec3 getPosition() {
        Level level = this.mob.level();
        Vec3 best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            Vec3 candidate = LandRandomPos.getPos(this.mob, CANDIDATE_HORIZONTAL, CANDIDATE_VERTICAL);
            if (candidate == null) {
                continue;
            }
            int score = scoreCandidate(level, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Point-check habitat score for {@code candidate}: 0-3 based on the
     * three condition flags. See class javadoc for the criteria.
     */
    static int scoreCandidate(Level level, Vec3 candidate) {
        BlockPos pos = BlockPos.containing(candidate);
        int score = 0;
        if (level.getMaxLocalRawBrightness(pos) >= AnimalWeightsConfig.SERVER.lightThreshold.get()) {
            score++;
        }
        if (hasWaterNearby(level, pos)) {
            score++;
        }
        if (hasGrazingNearby(level, pos)) {
            score++;
        }
        return score;
    }

    /**
     * True if any cell within {@link #WATER_SEARCH_RADIUS} chess-distance of
     * {@code pos} (at the same Y or one Y below) is a water source or a
     * water-filled cauldron. Matches the semantics
     * {@code DailyEvalHandler.checkWater} uses but widens the search radius
     * because the candidate is a *destination* — we want to know if water
     * is reachable from there, not just adjacent.
     */
    private static boolean hasWaterNearby(Level level, BlockPos pos) {
        for (int dx = -WATER_SEARCH_RADIUS; dx <= WATER_SEARCH_RADIUS; dx++) {
            for (int dz = -WATER_SEARCH_RADIUS; dz <= WATER_SEARCH_RADIUS; dz++) {
                BlockPos sample = pos.offset(dx, 0, dz);
                if (isWaterOrFilledCauldron(level, sample)) {
                    return true;
                }
                if (isWaterOrFilledCauldron(level, sample.below())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWaterOrFilledCauldron(Level level, BlockPos pos) {
        var fluid = level.getFluidState(pos);
        if (fluid.is(Fluids.WATER) && fluid.isSource()) {
            return true;
        }
        return level.getBlockState(pos).is(Blocks.WATER_CAULDRON);
    }

    /**
     * True if {@code pos} (or any horizontally-adjacent cell) has a grass
     * block or moss block directly below — i.e. a grazable surface within
     * one step.
     */
    private static boolean hasGrazingNearby(Level level, BlockPos pos) {
        if (isGrazing(level, pos.below())) {
            return true;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (isGrazing(level, pos.relative(dir).below())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGrazing(Level level, BlockPos pos) {
        var block = level.getBlockState(pos).getBlock();
        return block == Blocks.GRASS_BLOCK || block == Blocks.MOSS_BLOCK;
    }
}
