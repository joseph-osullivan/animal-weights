package io.github.josephosullivan.animalweights.unit;

import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tier-1 verification of the locked-in delta table from
 * {@code docs/workflow-runs/001-animal-weights-core/design.md}.
 *
 * <p>The table is the single piece of data the daily evaluation uses to
 * decide how weight moves. Encoding it as a constant array makes it easy to
 * verify here, with no Minecraft runtime required.
 */
class DeltaTableTest {

    @Test
    void delta_for_zero_conditions_is_minus_two() {
        assertEquals(-2, AnimalWeightsTuning.DELTA_BY_CONDITIONS[0]);
    }

    @Test
    void delta_for_one_condition_is_minus_two() {
        assertEquals(-2, AnimalWeightsTuning.DELTA_BY_CONDITIONS[1]);
    }

    @Test
    void delta_for_two_conditions_is_minus_one() {
        assertEquals(-1, AnimalWeightsTuning.DELTA_BY_CONDITIONS[2]);
    }

    @Test
    void delta_for_three_conditions_is_zero() {
        assertEquals(0, AnimalWeightsTuning.DELTA_BY_CONDITIONS[3]);
    }

    @Test
    void delta_for_four_conditions_is_plus_one() {
        assertEquals(+1, AnimalWeightsTuning.DELTA_BY_CONDITIONS[4]);
    }

    @Test
    void delta_table_has_exactly_five_entries_covering_zero_through_four() {
        // Defensive: if someone extends the table to six conditions without
        // updating callers, runEvaluation would happily index past the
        // designed range. Pin the length so that change is intentional.
        assertEquals(5, AnimalWeightsTuning.DELTA_BY_CONDITIONS.length);
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
        assertEquals(expectedDelta, AnimalWeightsTuning.DELTA_BY_CONDITIONS[conditionsMet]);
    }
}
