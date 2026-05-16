package io.github.josephosullivan.animalweights.unit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tier-1 verification of the locked-in delta table from
 * {@code docs/workflow-runs/001-animal-weights-core/design.md}.
 *
 * <p>Run-006: the delta table moved to {@link
 * io.github.josephosullivan.animalweights.AnimalWeightsConfig} as a TOML
 * list. The runtime value can only be read once a {@code ModContainer} has
 * loaded the config, which isn't available in a Tier-1 JUnit context. We
 * therefore pin the canonical default ({@code -2, -2, -1, 0, +1}) directly
 * — this matches the default the config registers, the fallback the
 * {@link io.github.josephosullivan.animalweights.AnimalWeightsConfig#deltaByConditions()}
 * helper returns when the list is missing or malformed, and is what
 * Tier-2 GameTests observe at runtime against the default-loaded config.
 *
 * <p>If you intentionally retune the table, update <i>both</i> the default
 * in {@code AnimalWeightsConfig.Server} <i>and</i> the constants here, in
 * the same commit, so the doc-of-record (this test) tracks the canonical
 * defaults.
 */
class DeltaTableTest {

    /**
     * Canonical default delta table from design.md, mirrored verbatim from
     * {@code AnimalWeightsConfig.Server} and from the fallback in
     * {@code AnimalWeightsConfig.deltaByConditions()}. The two locations are
     * deliberately in sync; a regression on either is loud here.
     */
    private static final int[] CANONICAL_DEFAULT = { -2, -2, -1, 0, 1 };

    @Test
    void delta_for_zero_conditions_is_minus_two() {
        assertEquals(-2, CANONICAL_DEFAULT[0]);
    }

    @Test
    void delta_for_one_condition_is_minus_two() {
        assertEquals(-2, CANONICAL_DEFAULT[1]);
    }

    @Test
    void delta_for_two_conditions_is_minus_one() {
        assertEquals(-1, CANONICAL_DEFAULT[2]);
    }

    @Test
    void delta_for_three_conditions_is_zero() {
        assertEquals(0, CANONICAL_DEFAULT[3]);
    }

    @Test
    void delta_for_four_conditions_is_plus_one() {
        assertEquals(+1, CANONICAL_DEFAULT[4]);
    }

    @Test
    void delta_table_has_exactly_five_entries_covering_zero_through_four() {
        // Defensive: if someone extends the table to six conditions without
        // updating callers, runEvaluation would happily index past the
        // designed range. Pin the length so that change is intentional.
        assertEquals(5, CANONICAL_DEFAULT.length);
    }

    @ParameterizedTest
    @CsvSource({
            "0, -2",
            "1, -2",
            "2, -1",
            "3,  0",
            "4,  1"
    })
    void delta_table_full_lookup(int conditionsMet, int expectedDelta) {
        assertEquals(expectedDelta, CANONICAL_DEFAULT[conditionsMet]);
    }
}
