package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.AnimalWeightsConfig;
import io.github.josephosullivan.animalweights.AnimalWeightsTags;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

import java.util.Optional;
import java.util.Set;

/**
 * Scales primary drops and experience for Cow/Pig/Sheep/Chicken/Rabbit based
 * on their stored {@link AnimalWeightAttachment} weight.
 *
 * <p>Formula (multiplicative, Option B — locked in run-005
 * {@code decisions.md} Q2):
 * <pre>
 *   primary-drop stack count: stack.setCount(stack.getCount() * weight)
 *   xp dropped:               event.getDroppedExperience() * weight
 * </pre>
 *
 * <p>Weight 1 (default) is a multiplier of 1, so vanilla behavior is preserved
 * for unmanaged animals. Weight 0 ("sick") does NOT multiply by zero — instead
 * the kill is routed to {@link #applySickDropReduction}, a cap-and-cull branch
 * that mutates the drops independently. Weight values 2..8 multiply both stack
 * count and XP by the weight itself.
 *
 * <p>Looting is implicitly multiplicative now: the loot table rolls before
 * {@link LivingDropsEvent} fires, so the base stack count already has Looting
 * applied; we then multiply by weight on top. A weight-4 cow killed with
 * Looting III drops {@code (Looting-rolled base) * 4}.
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
 *
 * <p><b>Village/water passive bonus</b> (v0.2.0, run-005 task-8): in addition
 * to the multiplicative weight scaling above, an animal killed within
 * {@code AnimalWeightsConfig.SERVER.villageWaterRadius} blocks of a village
 * (per {@link ServerLevel#isVillage(BlockPos)}) or a water source block grants
 * {@code +1} extra count on each primary drop and {@code +1} extra XP. The
 * bonus is computed once at kill time (drop events are infrequent), is
 * independent of weight, and does <b>not</b> apply to sick mobs (weight 0)
 * — they take the early-return through {@link #applySickDropReduction} before
 * the bonus check.
 *
 * <p><b>Modded-mob fallback path</b> (v0.2.0, run-006): for any entity that
 * passes {@link AnimalWeightAttachment#isTracked} via the
 * {@code #animalweights:tracked} tag but is <b>not</b> one of the five curated
 * vanilla species above (i.e. anything added to the tag by a modpack
 * datapack), we re-roll the entity's own vanilla loot table {@code weight - 1}
 * extra times to multiply its drops. This is more permissive than the curated
 * path (every non-empty item from the loot table is scaled, not just a hand
 * picked set) but is gated by the
 * {@code #animalweights:scaling_excluded_drops} item tag so rare drops like
 * {@code minecraft:rabbit_foot} stay un-amplified. The village/water +1 bonus
 * applies to each surviving stack, matching the curated path.
 *
 * <p>The modded path preserves Looting because each re-roll uses a fresh
 * {@link LootParams} built from the kill's {@link net.minecraft.world.damagesource.DamageSource}
 * — same provenance the vanilla roll consumed. XP scaling already covers the
 * modded path because {@link #onLivingExperienceDrop} gates only on
 * {@code isTracked} (no species check).
 *
 * <p><b>Tier-2 coverage gap</b>: testing the modded fallback path requires
 * spawning a non-vanilla animal in the test environment, which is not
 * possible from a single-jar GameTest. The path is exercised in manual
 * playtests against real modpack scenarios. Promoting to Tier-2 is deferred
 * to v0.3.0, when a fixture-mob harness becomes available.
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

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (!AnimalWeightAttachment.isTracked(entity)) {
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
        // Village/water passive bonus check goes AFTER the sick early-return so
        // sick mobs do not get the bonus. Computed once at kill time — drop
        // events are infrequent, so this is cheap relative to per-tick work.
        boolean villageBonus = isNearVillageOrWater(entity);

        // Clamp upper bound defensively; the attachment is clamped on write
        // but we don't trust unconstrained reads in the hot path.
        int multiplier = Math.min(weight, AnimalWeightsTuning.WEIGHT_MAX);

        if (isCuratedSpecies(entity)) {
            // Curated vanilla path: walk the existing drops and multiply only
            // the items in the per-species PRIMARY_DROPS sets. Weight 1 is a
            // multiplier of 1 (no-op for counts), but we still walk the drops
            // to apply the village/water +1 bonus when applicable.
            for (ItemEntity drop : event.getDrops()) {
                ItemStack stack = drop.getItem();
                if (!isPrimaryDropFor(entity, stack)) {
                    continue;
                }
                if (multiplier >= 2) {
                    stack.setCount(stack.getCount() * multiplier);
                }
                if (villageBonus) {
                    stack.grow(1);
                }
            }
            return;
        }

        // Modded fallback path: re-roll the entity's vanilla loot table
        // (multiplier - 1) extra times. Each surviving stack respects the
        // SCALING_EXCLUDED_DROPS tag and inherits the village/water bonus.
        applyModdedDropScaling(event, entity, multiplier, villageBonus);
    }

    /**
     * Returns {@code true} if {@code entity} matches one of the five curated
     * vanilla species (cow/mooshroom via {@link AbstractCow}, pig, sheep,
     * chicken, rabbit). Mirrors the species set keyed by
     * {@link #isPrimaryDropFor} — kept as a separate predicate so the modded
     * path can branch before walking drops.
     */
    private static boolean isCuratedSpecies(LivingEntity entity) {
        return entity instanceof AbstractCow
                || entity instanceof Pig
                || entity instanceof Sheep
                || entity instanceof Chicken
                || entity instanceof Rabbit;
    }

    /**
     * Modded-mob fallback: re-roll the entity's vanilla loot table
     * {@code multiplier - 1} extra times and append each surviving stack as a
     * fresh {@link ItemEntity} on the {@link LivingDropsEvent}. Rolls preserve
     * the killing context ({@link LootContextParams#DAMAGE_SOURCE},
     * {@link LootContextParams#ATTACKING_ENTITY},
     * {@link LootContextParams#DIRECT_ATTACKING_ENTITY}) so Looting still
     * applies to each re-roll.
     *
     * <p>Stacks whose item is in the
     * {@link AnimalWeightsTags#SCALING_EXCLUDED_DROPS} tag are dropped on the
     * floor — they're rare drops that would turn weight into an extra Looting
     * tier if scaled. The village/water +1 bonus is applied to each surviving
     * stack, matching the curated path's per-drop semantics.
     *
     * <p>No-ops if the entity has no loot table ({@link Entity#getLootTable}
     * returns empty) or the resolved level isn't a {@link ServerLevel}
     * (defensive — {@link LivingDropsEvent} fires server-side).
     */
    private static void applyModdedDropScaling(LivingDropsEvent event, LivingEntity entity,
                                                int multiplier, boolean villageBonus) {
        if (multiplier < 2 && !villageBonus) {
            // Weight 1 with no village/water bonus: nothing to add.
            return;
        }
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Optional<ResourceKey<LootTable>> lootKey = entity.getLootTable();
        if (lootKey.isEmpty()) {
            return;
        }
        LootTable lootTable = serverLevel.getServer().reloadableRegistries().getLootTable(lootKey.get());
        if (lootTable == LootTable.EMPTY) {
            return;
        }

        LootParams.Builder params = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.THIS_ENTITY, entity)
                .withParameter(LootContextParams.ORIGIN, entity.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, event.getSource());
        if (event.getSource().getEntity() != null) {
            params = params.withParameter(LootContextParams.ATTACKING_ENTITY, event.getSource().getEntity());
        }
        if (event.getSource().getDirectEntity() != null) {
            params = params.withParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, event.getSource().getDirectEntity());
        }
        LootParams built = params.create(LootContextParamSets.ENTITY);

        // Apply the village/water bonus to the ORIGINAL vanilla-rolled drops
        // first, BEFORE we append any re-rolls. This snapshot-by-iteration
        // pattern ensures we mutate each original stack exactly once and the
        // re-rolled stacks (added later) get their bonus applied inline.
        if (villageBonus) {
            for (ItemEntity drop : event.getDrops()) {
                ItemStack stack = drop.getItem();
                if (stack.isEmpty() || stack.is(AnimalWeightsTags.SCALING_EXCLUDED_DROPS)) {
                    continue;
                }
                stack.grow(1);
            }
        }

        int extraRolls = Math.max(0, multiplier - 1);
        for (int i = 0; i < extraRolls; i++) {
            ObjectArrayList<ItemStack> rolled = lootTable.getRandomItems(built);
            for (ItemStack stack : rolled) {
                if (stack.isEmpty()) {
                    continue;
                }
                if (stack.is(AnimalWeightsTags.SCALING_EXCLUDED_DROPS)) {
                    continue;
                }
                if (villageBonus) {
                    stack.grow(1);
                }
                ItemEntity itemEntity = new ItemEntity(serverLevel,
                        entity.getX(), entity.getY(), entity.getZ(), stack);
                itemEntity.setDefaultPickUpDelay();
                event.getDrops().add(itemEntity);
            }
        }
    }

    /**
     * Returns {@code true} if {@code entity} is within
     * {@code AnimalWeightsConfig.SERVER.villageWaterRadius} blocks of a village
     * (per {@link ServerLevel#isVillage(BlockPos)}) or any water source block.
     * The village check short-circuits the water scan when it returns true.
     *
     * <p>Only meaningful on the server — returns {@code false} unconditionally
     * if the entity's level is not a {@link ServerLevel}. {@code LivingDropsEvent}
     * fires server-side so this guard is defensive.
     */
    private static boolean isNearVillageOrWater(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel sl)) {
            return false;
        }
        BlockPos pos = entity.blockPosition();
        if (sl.isVillage(pos)) {
            return true;
        }
        // Configured half-extent AABB scan for any water source block.
        double size = AnimalWeightsConfig.SERVER.villageWaterRadius.get() * 2.0;
        AABB box = AABB.ofSize(Vec3.atCenterOf(pos), size, size, size);
        return BlockPos.betweenClosedStream(box).anyMatch(p ->
                sl.getFluidState(p).is(Fluids.WATER) && sl.getFluidState(p).isSource());
    }

    /**
     * Sick-mob drop reduction. Iterates the event's drops, and for each
     * primary-drop ItemEntity: with the configured {@code sick_drop_removal_chance}
     * probability, removes the entity entirely; otherwise clamps the stack
     * count to 1. Non-primary drops (uncommon mob loot, mod additions) are
     * left untouched.
     */
    private static void applySickDropReduction(LivingDropsEvent event, LivingEntity entity) {
        var iter = event.getDrops().iterator();
        // Coerce once: the config stores a double; the predicate compares
        // against a float random draw. Read once per kill (drop events are
        // infrequent).
        float removalChance = AnimalWeightsConfig.SERVER.sickDropRemovalChance.get().floatValue();
        while (iter.hasNext()) {
            ItemEntity drop = iter.next();
            if (!isPrimaryDropFor(entity, drop.getItem())) {
                continue;
            }
            if (entity.level().getRandom().nextFloat() < removalChance) {
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
        if (!AnimalWeightAttachment.isTracked(entity)) {
            return;
        }
        if (entity instanceof Mob mob && mob.isBaby()) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return; // tag could legally contain non-LivingEntity types; gate before cast
        }
        int weight = AnimalWeightAttachment.get(living);
        // Weight 0 (sick) leaves XP untouched and skips the bonus — same
        // exclusion as in onLivingDrops (sick mobs get neither multiplier
        // nor village/water +1).
        if (weight == 0) {
            return;
        }
        int multiplier = Math.min(weight, AnimalWeightsTuning.WEIGHT_MAX);
        int xp = event.getDroppedExperience();
        if (multiplier >= 2) {
            xp = xp * multiplier;
        }
        if (isNearVillageOrWater(living)) {
            xp = xp + 1;
        }
        if (xp != event.getDroppedExperience()) {
            event.setDroppedExperience(xp);
        }
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
