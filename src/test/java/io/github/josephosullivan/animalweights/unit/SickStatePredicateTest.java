package io.github.josephosullivan.animalweights.unit;

import io.github.josephosullivan.animalweights.event.SickStateHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-int predicate test for {@link SickStateHandler#isSick(int)}.
 *
 * <p>The full handler needs a {@code ServerLevel} (entity ticking, breed event)
 * and so is exercised in Tier-2 GameTests. The predicate itself is just
 * {@code weight == 0}; isolating it as a public helper lets us keep the
 * branching logic test-covered without booting an MC server.
 */
class SickStatePredicateTest {

    @Test
    void zero_weight_is_sick() {
        assertTrue(SickStateHandler.isSick(0));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 4, 5, 6, 7, 8 })
    void in_range_positive_weights_are_not_sick(int weight) {
        assertFalse(SickStateHandler.isSick(weight));
    }

    @Test
    void negative_weight_is_treated_as_not_sick() {
        // Negative inputs should never reach storage (clamp in
        // AnimalWeightAttachment.set guarantees this). Defensive contract:
        // if a caller bypasses the helper, we still degrade to "not sick"
        // rather than re-trigger the slowness effect indefinitely.
        assertFalse(SickStateHandler.isSick(-1));
    }

    @Test
    void max_weight_is_not_sick() {
        assertFalse(SickStateHandler.isSick(8));
    }
}
