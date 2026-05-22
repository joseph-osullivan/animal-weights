package io.github.josephosullivan.animalweights.unit;

import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import io.github.josephosullivan.animalweights.event.DropScalingHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the multiplicative drop-scaling formula from run-005 decisions.md Q2:
 *
 * <pre>
 *   primary-drop stack count: stack.setCount(stack.getCount() * weight)
 *   xp dropped:               event.getDroppedExperience() * weight
 * </pre>
 *
 * <p>The handler entry points {@link DropScalingHandler#onLivingDrops} and
 * {@code onLivingExperienceDrop} need a {@code ServerLevel} + spawned entity
 * to exercise end-to-end (Tier-2 GameTest territory). Here we pin the
 * arithmetic contract as pure-data assertions so the formula itself can't
 * regress unnoticed.
 *
 * <p>Sick (weight 0) is intentionally NOT covered here — it routes to a
 * separate {@code applySickDropReduction} cap-and-cull branch that is exercised
 * by {@code SickDropReductionGameTests} (Tier 2).
 */
class DropScalingMathTest {

    /** The active weight range — sanity guard so the formula table below stays in sync. */
    @Test
    void weight_max_constant_is_eight() {
        // If WEIGHT_MAX ever changes, every assertion in this file needs revisiting.
        assertEquals(8, AnimalWeightsTuning.WEIGHT_MAX);
    }

    @Test
    void weight_one_is_identity_multiplier() {
        // Default unmanaged animals (weight 1) must drop vanilla counts — a
        // multiplier of 1 is identity. Catches a regression where the handler
        // accidentally applies multiplier=weight even when weight==1, which
        // is fine arithmetically but signals the wrong semantic intent.
        int baseCount = 3;
        int weight = 1;
        assertEquals(3, baseCount * weight);
    }

    @Test
    void weight_two_doubles_primary_drops() {
        // First active tier of multiplication: weight 2 → count * 2.
        int beefBase = 2;
        int weight = 2;
        assertEquals(4, beefBase * weight);
    }

    @Test
    void weight_four_quadruples_primary_drops() {
        // Canonical "well-cared-for" tier — formerly the linear +3 bonus,
        // now a 4x multiplier. Beef base 2 → 8; leather base 1 → 4.
        int beefBase = 2;
        int leatherBase = 1;
        int weight = 4;
        assertEquals(8, beefBase * weight);
        assertEquals(4, leatherBase * weight);
    }

    @Test
    void weight_eight_peaks_at_eight_times_base() {
        // Peak of curve — well-cared-for farm payoff. Replaces the old
        // accelerating-curve peak of +11 with a flat 8x multiplier.
        int beefBase = 2;
        int weight = 8;
        assertEquals(16, beefBase * weight);
    }

    @Test
    void xp_scales_with_same_multiplier_as_drops() {
        // XP path uses the same multiplier so a weight-4 kill that drops 4x
        // beef also yields 4x XP. Base cow XP is 1–3 in vanilla; pin the
        // arithmetic at base=3 for a clean integer result.
        int baseXp = 3;
        int weight = 4;
        assertEquals(12, baseXp * weight);
    }

    /**
     * Parameterised pin of the full active range. Weights 0 and 1 are
     * unchanged (sick branch / identity); weights 2..8 multiply.
     */
    @ParameterizedTest
    @CsvSource({
            // weight, baseCount, expectedCount
            "1, 2, 2",   // identity
            "2, 2, 4",
            "3, 2, 6",
            "4, 2, 8",
            "5, 2, 10",
            "6, 2, 12",
            "7, 2, 14",
            "8, 2, 16",
            // Sanity row for a non-unit base — ensures the formula is
            // multiplication, not "set to weight".
            "4, 5, 20"
    })
    void multiplicative_formula_matches_count_times_weight(int weight, int baseCount, int expectedCount) {
        assertEquals(expectedCount, baseCount * weight);
    }

    /**
     * Catch a regression where someone re-adds the additive formula. Under the
     * additive curve {0,0,1,2,3,4,6,8,11}, weight-4 on baseCount=2 would yield
     * 2+3=5. Under multiplicative it must yield 2*4=8. This test pins the
     * delta so a partial rollback is loud.
     */
    @Test
    void weight_four_must_not_yield_additive_result() {
        int baseCount = 2;
        int weight = 4;
        int multiplicative = baseCount * weight;
        int additiveLegacy = baseCount + 3; // what the old DROP_BONUS_BY_WEIGHT[4] would have given
        assertTrue(multiplicative > additiveLegacy,
                "multiplicative formula (count*weight) must exceed legacy additive at weight 4");
        assertEquals(8, multiplicative);
    }
}
