package io.github.josephosullivan.animalweights;

/**
 * Centralized tuning constants for the Animal Weights mechanic.
 *
 * <p>No config system yet — change values here and recompile. Names match
 * the locked-decisions section of {@code docs/workflow-runs/001-animal-weights-core/design.md}.
 */
public final class AnimalWeightsTuning {

    private AnimalWeightsTuning() {
        // utility class — no instances
    }

    /** Minimum weight value (inclusive). Below this, weight is clamped to 0. */
    public static final int WEIGHT_MIN = 0;

    /** Maximum weight value (inclusive). Above this, weight is clamped to 8. */
    public static final int WEIGHT_MAX = 8;

    /** Default weight assigned to a newly observed Cow/Pig/Sheep. */
    public static final int WEIGHT_DEFAULT = 1;

    /**
     * Half-extent (in blocks) of the cube searched around a mob when evaluating
     * the four daily-condition checks (light, water, grazing surface).
     */
    public static final int EVAL_RADIUS_BLOCKS = 8;

    /** Block-light level (0–15) considered bright enough to satisfy the "light source nearby" check. */
    public static final int LIGHT_THRESHOLD = 14;

    /**
     * Edge length of the horizontal box centered on the mob used for the
     * "stretching room" (no-crowding) check. 3 means a 3×3 horizontal area.
     *
     * <p>Retained as a legacy constant — the active implementation has moved to
     * the BFS-reachability rule (see {@link #MIN_ROAMING_CELLS}).
     */
    public static final int STRETCHING_BOX = 3;

    /**
     * Minimum count of walkable cells reachable via BFS from the mob's standing
     * block for the "stretching room" condition to pass. 6 means roughly a
     * 2×3 walkable corridor — enough that a single mob in a 2×2 fenced pen
     * fails, but a normal 3×3 or larger enclosure passes.
     */
    public static final int MIN_ROAMING_CELLS = 6;

    /**
     * Maximum number of cells the reachability BFS will enumerate before
     * terminating. Bounds compute cost per mob per dawn evaluation. The BFS
     * also bounds its expansion by {@link #EVAL_RADIUS_BLOCKS} in any direction.
     */
    public static final int ROAMING_MAX_BFS_CELLS = 32;

    /**
     * Bonus added to each primary drop's stack count (and to dropped XP) as
     * a function of weight. Indexed by weight 0–8. Linear through w=4
     * (matches vanilla expectation that a "normal" cow drops baseline +
     * small bonus), then accelerates so weight 8 is meaningfully more
     * rewarding than the linear extrapolation would give.
     *
     * <ul>
     *   <li>w=1 →  0 (vanilla)</li>
     *   <li>w=2 →  +1</li>
     *   <li>w=3 →  +2</li>
     *   <li>w=4 →  +3</li>
     *   <li>w=5 →  +4</li>
     *   <li>w=6 →  +6</li>
     *   <li>w=7 →  +8</li>
     *   <li>w=8 → +11 (peak — well-cared-for farm payoff)</li>
     * </ul>
     *
     * <p>Compared to the previous linear formula {@code max(0, weight − 1)},
     * weights 1–5 are identical, w=6 gains +1, w=7 gains +2, w=8 gains +4.
     * Weight 0 (sick) does not use this table — sick mobs have their own
     * cap-and-cull logic.
     */
    public static final int[] DROP_BONUS_BY_WEIGHT = { 0, 0, 1, 2, 3, 4, 6, 8, 11 };

    /**
     * Probability (0.0–1.0) that each primary drop from a sick (weight=0) mob
     * is removed entirely instead of being capped to a single item. Stacks
     * that aren't removed are clamped to count 1. 0.5 means roughly half of
     * expected drops vanish; 0.0 means just cap-to-1; 1.0 means sick mobs
     * drop nothing primary.
     */
    public static final float SICK_DROP_REMOVAL_CHANCE = 0.5f;

    /**
     * Per-day weight delta indexed by the number of satisfied conditions (0–4).
     *
     * <ul>
     *   <li>0 conditions met → -2</li>
     *   <li>1 condition  met → -2</li>
     *   <li>2 conditions met → -1</li>
     *   <li>3 conditions met →  0</li>
     *   <li>4 conditions met → +1</li>
     * </ul>
     */
    public static final int[] DELTA_BY_CONDITIONS = { -2, -2, -1, 0, +1 };
}
