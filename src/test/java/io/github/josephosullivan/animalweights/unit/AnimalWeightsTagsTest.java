package io.github.josephosullivan.animalweights.unit;

import io.github.josephosullivan.animalweights.AnimalWeightsTags;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-data check on the {@link AnimalWeightsTags#TRACKED} key shape.
 *
 * <p>We can't verify the JSON content from Tier-1 — tag resolution requires
 * a registry context (server boot) — but we can pin the namespace, path,
 * and registry the key declares. The actual JSON-to-runtime resolution is
 * covered by every Tier-2 GameTest that spawns a vanilla cow/pig/sheep/
 * chicken/rabbit/mooshroom and asserts weight, drop scaling, or sick state
 * behaviour: if the tag JSON path or values were wrong, those tests would
 * fail at the first handler call.
 */
class AnimalWeightsTagsTest {

    @Test
    void tracked_tag_uses_correct_namespace_and_path() {
        assertEquals("animalweights", AnimalWeightsTags.TRACKED.location().getNamespace());
        assertEquals("tracked", AnimalWeightsTags.TRACKED.location().getPath());
    }

    @Test
    void tracked_tag_targets_entity_type_registry() {
        assertEquals(Registries.ENTITY_TYPE, AnimalWeightsTags.TRACKED.registry());
    }

    @Test
    void scaling_excluded_tag_uses_correct_namespace_and_path() {
        assertEquals("animalweights",
                AnimalWeightsTags.SCALING_EXCLUDED_DROPS.location().getNamespace());
        assertEquals("scaling_excluded_drops",
                AnimalWeightsTags.SCALING_EXCLUDED_DROPS.location().getPath());
        assertEquals(Registries.ITEM, AnimalWeightsTags.SCALING_EXCLUDED_DROPS.registry());
    }
}
