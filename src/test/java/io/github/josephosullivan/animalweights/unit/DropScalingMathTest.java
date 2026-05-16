package io.github.josephosullivan.animalweights.unit;

import io.github.josephosullivan.animalweights.event.DropScalingHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-int bonus math for {@link DropScalingHandler#bonusFor(int)}.
 *
 * <p>The handler entry points {@code onLivingDrops} / {@code onLivingExperienceDrop}
 * need a {@code ServerLevel} + spawned entity to exercise end-to-end, so the
 * stack-grow path is covered by a Tier-2 GameTest in a follow-up run. Here we
 * pin the lookup table itself — accelerating curve toward weight 8.
 */
class DropScalingMathTest {

    @Test
    void weight_one_yields_no_bonus() {
        assertEquals(0, DropScalingHandler.bonusFor(1));
    }

    @Test
    void weight_four_still_yields_linear_bonus_of_three() {
        // Curve and linear agree through w=5; w=4 is in the linear segment.
        assertEquals(3, DropScalingHandler.bonusFor(4));
    }

    @Test
    void weight_eight_yields_peak_bonus_of_eleven() {
        // Acceleration kicks in past w=5; w=8 is the well-cared-for payoff.
        assertEquals(11, DropScalingHandler.bonusFor(8));
    }

    @Test
    void weight_zero_sick_mob_yields_no_bonus() {
        // Sick mobs (weight 0) must not subtract from baseline drops; the floor
        // at 0 prevents negative grow() calls or XP loss.
        assertEquals(0, DropScalingHandler.bonusFor(0));
    }

    @Test
    void weight_negative_one_clamped_to_zero_just_in_case() {
        // Defence in depth: even if a caller hands us a sub-clamp value, bonus
        // can never go negative.
        assertEquals(0, DropScalingHandler.bonusFor(-1));
    }

    @Test
    void weight_above_max_clamps_to_peak() {
        // Out-of-range positive inputs clamp to WEIGHT_MAX (8) rather than throw.
        assertEquals(11, DropScalingHandler.bonusFor(99));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "1, 0",
            "2, 1",
            "3, 2",
            "4, 3",
            "5, 4",
            "6, 6",
            "7, 8",
            "8, 11"
    })
    void bonus_table_matches_curve(int weight, int expectedBonus) {
        assertEquals(expectedBonus, DropScalingHandler.bonusFor(weight));
    }
}
