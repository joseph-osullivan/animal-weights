package io.github.josephosullivan.animalweights;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/**
 * Centralised {@link TagKey} declarations for this mod.
 *
 * <p>The {@link #TRACKED} tag is the single source of truth for which entity
 * types receive Animal Weights behaviour — weight assignment, sick state,
 * drop scaling, habitat AI, the display overlay, and the client tint. It
 * ships with a default JSON at
 * {@code data/animalweights/tags/entity_type/tracked.json} listing the six
 * vanilla target species (cow, mooshroom, pig, sheep, chicken, rabbit).
 *
 * <p>Other mods and modpack datapacks can extend coverage to modded animals
 * by contributing to the same tag. Tag values are union-merged across all
 * datapacks, so multiple contributors compose without conflict — no
 * coordination required.
 */
public final class AnimalWeightsTags {

    private AnimalWeightsTags() {
        // tag-key holder — no instances
    }

    /**
     * Entity types that participate in the weight gameplay. Read by every
     * server handler and the client render mixin via
     * {@link AnimalWeightAttachment#isTracked}.
     */
    public static final TagKey<EntityType<?>> TRACKED =
            TagKey.create(Registries.ENTITY_TYPE,
                    Identifier.fromNamespaceAndPath(AnimalWeights.MOD_ID, "tracked"));

    /**
     * Items that must NOT have their stack count multiplied by the modded-mob
     * drop-scaling fallback path. Used only on the tag-extended modded path in
     * {@code DropScalingHandler.onLivingDrops} — the curated vanilla path
     * (cow/pig/sheep/chicken/rabbit) uses its own hard-coded primary-drop sets
     * and is untouched by this tag.
     *
     * <p>Default tag JSON ships with {@code minecraft:rabbit_foot}: it's a rare
     * drop (~10% base chance), and scaling it would turn weight into an extra
     * Looting tier for any modded rabbit-like mob whose loot table rolls it.
     * Modpack authors can add their own rare-drop items (foreign equivalents
     * of rabbit_foot, modded trinkets, etc.) by contributing to this tag from
     * their own datapack.
     */
    public static final TagKey<Item> SCALING_EXCLUDED_DROPS =
            TagKey.create(Registries.ITEM,
                    Identifier.fromNamespaceAndPath(AnimalWeights.MOD_ID, "scaling_excluded_drops"));
}
