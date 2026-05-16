package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

import java.util.Set;

/**
 * Scales primary drops and experience for Cow/Pig/Sheep based on their stored
 * {@link AnimalWeightAttachment} weight.
 *
 * <p>Formula (additive, locked in {@code design.md}):
 * <pre>
 *   bonus = max(0, weight - 1)
 *   primary-drop stack count: stack.grow(bonus)
 *   xp dropped:               base + bonus
 * </pre>
 *
 * <p>Weight 1 (default) produces a zero bonus, so vanilla behavior is preserved
 * for unmanaged animals. Weight 0 ("sick") also produces a zero bonus — we do
 * not subtract from base drops.
 *
 * <p>Only "primary" drops scale; secondary drops the entity might emit (rare
 * items, mob-loot-table additions from other mods) are left alone. The primary
 * list mirrors the table in {@code design.md} (extended in run-003):
 *
 * <ul>
 *   <li>Cow / Mooshroom (any {@link AbstractCow}) → beef / cooked beef / leather</li>
 *   <li>Pig → porkchop / cooked porkchop</li>
 *   <li>Sheep → mutton / cooked mutton / any wool (via {@code minecraft:wool} tag,
 *       which covers every dye colour without enumerating sixteen items).</li>
 *   <li>Chicken → chicken (raw) / cooked chicken / feather</li>
 *   <li>Rabbit → rabbit (raw) / cooked rabbit / rabbit hide.
 *       <b>NOT</b> {@code RABBIT_FOOT} — that's a rare drop (~10% base), and
 *       scaling it would turn weight into a Looting amplifier. Out of scope.</li>
 * </ul>
 *
 * <p>Mooshroom (vanilla {@code MushroomCow}) is auto-covered via
 * {@link AbstractCow}: in MC 26.1 {@code MushroomCow} is a sibling of
 * {@code Cow}, not a subclass, so {@code instanceof Cow} would miss it.
 */
@EventBusSubscriber(modid = AnimalWeights.MOD_ID)
public final class DropScalingHandler {

    private DropScalingHandler() {
        // event-only utility class — no instances
    }

    /**
     * Cow / Mooshroom primary drops: raw, cooked (when killed on fire), and
     * leather. Mooshroom drops the same loot table as Cow in vanilla so a
     * single shared set covers both species.
     */
    private static final Set<Item> COW_PRIMARY_DROPS = Set.of(
            Items.BEEF,
            Items.COOKED_BEEF,
            Items.LEATHER
    );

    /** Pig primary drops: raw and cooked porkchop. */
    private static final Set<Item> PIG_PRIMARY_DROPS = Set.of(
            Items.PORKCHOP,
            Items.COOKED_PORKCHOP
    );

    /**
     * Sheep "always-scaled" primary drops. Wool is matched via {@link ItemTags#WOOL}
     * in {@link #isPrimaryDropFor(LivingEntity, ItemStack)} so all sixteen dye
     * colours apply without per-item enumeration.
     */
    private static final Set<Item> SHEEP_PRIMARY_DROPS = Set.of(
            Items.MUTTON,
            Items.COOKED_MUTTON
    );

    /**
     * Chicken primary drops: raw chicken, cooked chicken (when killed on fire),
     * and feathers. All three roll on every kill (no "rare" gating to exclude).
     */
    private static final Set<Item> CHICKEN_PRIMARY_DROPS = Set.of(
            Items.CHICKEN,
            Items.COOKED_CHICKEN,
            Items.FEATHER
    );

    /**
     * Rabbit primary drops: raw rabbit, cooked rabbit (when killed on fire),
     * and rabbit hide. <b>{@code RABBIT_FOOT} is intentionally absent</b> —
     * it's a rare drop (vanilla ~10% base chance, modified by Looting); scaling
     * it would let weight act as an extra Looting tier. Out of scope per
     * run-003 design.
     */
    private static final Set<Item> RABBIT_PRIMARY_DROPS = Set.of(
            Items.RABBIT,
            Items.COOKED_RABBIT,
            Items.RABBIT_HIDE
    );

    /**
     * Pure-int helper: how much to grow primary drop stacks (and add to XP) for
     * an entity with the given weight. Looks up
     * {@link AnimalWeightsTuning#DROP_BONUS_BY_WEIGHT}; out-of-range inputs
     * (negative, &gt; WEIGHT_MAX) are floored / capped before lookup so the
     * helper never throws and never returns negative.
     *
     * <p>The table accelerates toward weight 8 — see the constant's javadoc
     * for the curve. Sick mobs (weight 0) get 0 bonus from this helper, but
     * the on-kill path for sick mobs runs through a separate cap-and-cull
     * branch in {@link #onLivingDrops}, not this function.
     *
     * <p>Tier-1 testable; no Minecraft dependencies.
     */
    public static int bonusFor(int weight) {
        int clamped = Math.max(0, Math.min(weight, AnimalWeightsTuning.WEIGHT_MAX));
        return AnimalWeightsTuning.DROP_BONUS_BY_WEIGHT[clamped];
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isTargetSpecies(entity)) {
            return;
        }
        if (entity instanceof Mob mob && mob.isBaby()) {
            return; // vanilla babies drop nothing; explicit guard for clarity
        }
        int weight = AnimalWeightAttachment.get(entity);

        if (weight == 0) {
            applySickDropReduction(event, entity);
            return;
        }

        int bonus = bonusFor(weight);
        if (bonus <= 0) {
            return;
        }
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (isPrimaryDropFor(entity, stack)) {
                stack.grow(bonus);
            }
        }
    }

    /**
     * Sick-mob drop reduction. Iterates the event's drops, and for each
     * primary-drop ItemEntity: with {@link AnimalWeightsTuning#SICK_DROP_REMOVAL_CHANCE}
     * probability, removes the entity entirely; otherwise clamps the stack
     * count to 1. Non-primary drops (uncommon mob loot, mod additions) are
     * left untouched.
     */
    private static void applySickDropReduction(LivingDropsEvent event, LivingEntity entity) {
        var iter = event.getDrops().iterator();
        while (iter.hasNext()) {
            ItemEntity drop = iter.next();
            if (!isPrimaryDropFor(entity, drop.getItem())) {
                continue;
            }
            if (entity.level().getRandom().nextFloat() < AnimalWeightsTuning.SICK_DROP_REMOVAL_CHANCE) {
                iter.remove();
                drop.discard();
                continue;
            }
            if (drop.getItem().getCount() > 1) {
                drop.getItem().setCount(1);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingExperienceDrop(LivingExperienceDropEvent event) {
        Entity entity = event.getEntity();
        if (!isTargetSpecies(entity)) {
            return;
        }
        if (entity instanceof Mob mob && mob.isBaby()) {
            return;
        }
        int weight = AnimalWeightAttachment.get((LivingEntity) entity);
        int bonus = bonusFor(weight);
        if (bonus <= 0) {
            return;
        }
        event.setDroppedExperience(event.getDroppedExperience() + bonus);
    }

    private static boolean isTargetSpecies(Entity entity) {
        // AbstractCow covers both Cow and MushroomCow in MC 26.1.
        return entity instanceof AbstractCow || entity instanceof Pig
                || entity instanceof Sheep || entity instanceof Chicken
                || entity instanceof Rabbit;
    }

    /**
     * Returns true if {@code stack} is one of the scaling-eligible drops for
     * {@code entity}'s species. Sheep wool is matched via the {@code minecraft:wool}
     * tag (covers every dye colour). Rabbit foot is excluded from the rabbit
     * set — see {@link #RABBIT_PRIMARY_DROPS} javadoc.
     */
    private static boolean isPrimaryDropFor(LivingEntity entity, ItemStack stack) {
        if (entity instanceof AbstractCow) {
            return COW_PRIMARY_DROPS.contains(stack.getItem());
        }
        if (entity instanceof Pig) {
            return PIG_PRIMARY_DROPS.contains(stack.getItem());
        }
        if (entity instanceof Sheep) {
            return SHEEP_PRIMARY_DROPS.contains(stack.getItem()) || stack.is(ItemTags.WOOL);
        }
        if (entity instanceof Chicken) {
            return CHICKEN_PRIMARY_DROPS.contains(stack.getItem());
        }
        if (entity instanceof Rabbit) {
            return RABBIT_PRIMARY_DROPS.contains(stack.getItem());
        }
        return false;
    }
}
