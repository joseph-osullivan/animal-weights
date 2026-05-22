package io.github.josephosullivan.animalweights;

/**
 * Save-format-coupled constants for the Animal Weights mechanic.
 *
 * <p><b>Why constants and not config?</b> The values in this class form part
 * of the on-disk save format — they bound the
 * {@link AnimalWeightAttachment} integer range and govern its clamping
 * behaviour. Changing {@link #WEIGHT_MIN} or {@link #WEIGHT_MAX} at runtime
 * (e.g. via a config TOML) would invalidate any saved attachment value that
 * falls outside the new range, and the
 * {@link AnimalWeightAttachment#clamp(int)} helper would silently coerce
 * historical values on next read. Both effects corrupt existing worlds.
 * These three constants therefore stay compile-time only.
 *
 * <p>All <em>tunable</em> values (eval radius, BFS cap, drop chances, delta
 * table, village/water radius, sick tint colour) live in
 * {@link AnimalWeightsConfig}, registered as
 * {@code config/animalweights-server.toml} (gameplay) and
 * {@code config/animalweights-client.toml} (visual). See that class for the
 * runtime-tunable knobs.
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
}
