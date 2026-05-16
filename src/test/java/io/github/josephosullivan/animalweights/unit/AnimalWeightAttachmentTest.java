package io.github.josephosullivan.animalweights.unit;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-int clamp math for {@link AnimalWeightAttachment}.
 *
 * <p>The {@code get}/{@code set} entry points require a {@code LivingEntity} bound
 * to a server-side registry, so default-value behavior is verified in
 * {@code WeightAttachmentGameTest} (Tier 2) instead. Here we cover only the
 * clamp helper, which has no Minecraft dependencies.
 */
class AnimalWeightAttachmentTest {

    @Test
    void clamp_zero_stays_at_min() {
        assertEquals(AnimalWeightsTuning.WEIGHT_MIN, AnimalWeightAttachment.clamp(0));
    }

    @Test
    void clamp_default_is_within_range() {
        assertEquals(AnimalWeightsTuning.WEIGHT_DEFAULT, AnimalWeightAttachment.clamp(AnimalWeightsTuning.WEIGHT_DEFAULT));
    }

    @Test
    void clamp_max_stays_at_max() {
        assertEquals(AnimalWeightsTuning.WEIGHT_MAX, AnimalWeightAttachment.clamp(AnimalWeightsTuning.WEIGHT_MAX));
    }

    @Test
    void clamp_negative_three_pins_to_min() {
        assertEquals(AnimalWeightsTuning.WEIGHT_MIN, AnimalWeightAttachment.clamp(-3));
    }

    @Test
    void clamp_ninety_nine_pins_to_max() {
        assertEquals(AnimalWeightsTuning.WEIGHT_MAX, AnimalWeightAttachment.clamp(99));
    }

    @Test
    void clamp_min_minus_one_pins_to_min() {
        assertEquals(AnimalWeightsTuning.WEIGHT_MIN, AnimalWeightAttachment.clamp(AnimalWeightsTuning.WEIGHT_MIN - 1));
    }

    @Test
    void clamp_max_plus_one_pins_to_max() {
        assertEquals(AnimalWeightsTuning.WEIGHT_MAX, AnimalWeightAttachment.clamp(AnimalWeightsTuning.WEIGHT_MAX + 1));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "1, 1",
            "4, 4",
            "8, 8",
            "-1, 0",
            "-100, 0",
            "9, 8",
            "1000, 8"
    })
    void clamp_table(int input, int expected) {
        assertEquals(expected, AnimalWeightAttachment.clamp(input));
    }
}
