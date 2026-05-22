package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.event.DropScalingHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Bodies for {@link DropScalingHandler} Tier-2 GameTests, asserting the spec
 * from run-002 {@code design.md} "Drop scaling" section.
 *
 * <p>Approach: construct vanilla loot drops as a list of {@link ItemEntity}
 * objects, then directly construct + post the {@link LivingDropsEvent} on
 * {@link NeoForge#EVENT_BUS}. This bypasses the brittle "kill the entity and
 * wait for vanilla loot tables to fire" path; the only thing under test here
 * is the {@link DropScalingHandler#onLivingDrops} response to the event.
 *
 * <p>Formula being asserted (Option B, run-005 decisions Q2): primary-drop
 * stack count is {@code stack.setCount(stack.getCount() * weight)} and dropped
 * XP is {@code event.getDroppedExperience() * weight} for {@code weight >= 2}.
 * Weight 1 leaves counts unchanged (identity multiplier); weight 0 routes to
 * sick-drop cap-and-cull. Boundary cases at weight 6 / 7 / 8 live in
 * {@code DropCurveGameTests}; this class covers cross-species coverage,
 * sick-drop cap-and-cull, and non-target rejection.
 */
public final class DropScalingGameTests {

    private DropScalingGameTests() {}

    /** Spawn position for victims in each test. */
    private static final BlockPos VICTIM_REL = new BlockPos(2, 2, 2);

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Build a single-stack {@link ItemEntity} positioned over the victim. The
     * production handler only reads {@code drop.getItem()} and mutates the
     * stack via {@code grow}, so the entity's world position is unimportant
     * — but the entity must be alive (i.e. {@code !removed}) to look real.
     */
    private static ItemEntity newDrop(GameTestHelper helper, BlockPos rel, Item item, int count) {
        BlockPos abs = helper.absolutePos(rel);
        return new ItemEntity(helper.getLevel(),
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                new ItemStack(item, count));
    }

    /** Same as {@link #newDrop(GameTestHelper, BlockPos, Item, int)} but with an explicit ItemStack. */
    private static ItemEntity newDropStack(GameTestHelper helper, BlockPos rel, ItemStack stack) {
        BlockPos abs = helper.absolutePos(rel);
        return new ItemEntity(helper.getLevel(),
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                stack);
    }

    /**
     * Post a {@link LivingDropsEvent} with the given victim and drops. The
     * production {@link DropScalingHandler#onLivingDrops} listener will run
     * synchronously on this thread (NeoForge bus is single-threaded for
     * server events) and mutate the {@link ItemStack#getCount stack counts}
     * in place if the victim is a target species with bonus > 0.
     */
    private static void postDropsEvent(LivingEntity victim, List<ItemEntity> drops, DamageSource source) {
        LivingDropsEvent event = new LivingDropsEvent(victim, source, drops, /* recentlyHit */ true);
        NeoForge.EVENT_BUS.post(event);
    }

    /** Helper for the simple weight-N kill scenario with default damage source. */
    private static void postDropsEventGeneric(GameTestHelper helper, LivingEntity victim, List<ItemEntity> drops) {
        postDropsEvent(victim, drops, helper.getLevel().damageSources().generic());
    }

    /** Find a drop with the given item type; assumes uniqueness (caller's responsibility). */
    private static ItemEntity findDrop(List<ItemEntity> drops, Item item) {
        for (ItemEntity drop : drops) {
            if (drop.getItem().is(item)) {
                return drop;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------------
    // Spec table — "Tests that catch real bugs"
    // ----------------------------------------------------------------------

    /**
     * Spec row: "kill weight-1 cow → vanilla beef + leather counts". Weight 1
     * is the default (unmanaged animal), so {@code bonus = 0}; baseline stack
     * sizes must be preserved.
     */
    public static void killWeightOneCowDropsVanillaCounts(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 1);

        ItemEntity beef = newDrop(helper, VICTIM_REL, Items.BEEF, 2);
        ItemEntity leather = newDrop(helper, VICTIM_REL, Items.LEATHER, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef, leather));
        postDropsEventGeneric(helper, cow, drops);

        if (beef.getItem().getCount() != 2) {
            helper.fail("weight-1 cow: beef expected 2 (unchanged); got " + beef.getItem().getCount()
                    + " — bonus accidentally added to baseline");
            return;
        }
        if (leather.getItem().getCount() != 1) {
            helper.fail("weight-1 cow: leather expected 1 (unchanged); got " + leather.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-4 cow → beef * 4, leather * 4, XP * 4". Pins the
     * multiplicative formula end-to-end for the canonical test case.
     */
    public static void killWeightFourCowDropsThreeExtra(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 4);

        ItemEntity beef = newDrop(helper, VICTIM_REL, Items.BEEF, 2);
        ItemEntity leather = newDrop(helper, VICTIM_REL, Items.LEATHER, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef, leather));
        postDropsEventGeneric(helper, cow, drops);

        if (beef.getItem().getCount() != 8) {
            helper.fail("weight-4 cow: beef expected 2 * 4 = 8; got " + beef.getItem().getCount());
            return;
        }
        if (leather.getItem().getCount() != 4) {
            helper.fail("weight-4 cow: leather expected 1 * 4 = 4; got " + leather.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row (originally): "kill weight-0 cow → vanilla counts". Updated for
     * run-004 section E: weight-0 cows now have sick-drop reduction applied —
     * each primary drop is either removed entirely (50% chance) or its stack
     * count capped to 1. Non-primary drops untouched.
     *
     * <p>Test asserts the cap-to-1 invariant: every surviving primary drop in
     * the event has count {@code <= 1}. Probabilistic removal is not asserted
     * (per design.md the probabilistic case is intentionally skipped).
     */
    public static void killWeightZeroCowDropsVanillaNoShrink(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 0);

        ItemEntity beef = newDrop(helper, VICTIM_REL, Items.BEEF, 5);
        ItemEntity leather = newDrop(helper, VICTIM_REL, Items.LEATHER, 5);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef, leather));
        postDropsEventGeneric(helper, cow, drops);

        // Every surviving primary drop must have count <= 1 (cap-to-1 invariant).
        for (ItemEntity drop : drops) {
            int count = drop.getItem().getCount();
            if (count > 1) {
                helper.fail("weight-0 cow: primary drop " + drop.getItem().getItem()
                        + " has count " + count + " (expected <= 1; sick drop reduction not capping)");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-4 pig → pork * 4". Species coverage — Pig must
     * be recognised as a primary-drop target.
     */
    public static void killWeightFourPigDropsThreeExtraPork(GameTestHelper helper) {
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, VICTIM_REL);
        AnimalWeightAttachment.set(pig, 4);

        ItemEntity pork = newDrop(helper, VICTIM_REL, Items.PORKCHOP, 2);
        List<ItemEntity> drops = new ArrayList<>(List.of(pork));
        postDropsEventGeneric(helper, pig, drops);

        if (pork.getItem().getCount() != 8) {
            helper.fail("weight-4 pig: pork expected 2 * 4 = 8; got " + pork.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-4 sheep (white wool) → mutton * 4, wool * 4".
     * Pins both the mutton entry and {@code ItemTags.WOOL} matching white
     * wool.
     */
    public static void killWeightFourSheepDropsThreeExtraMuttonAndWool(GameTestHelper helper) {
        Sheep sheep = helper.spawnWithNoFreeWill(EntityType.SHEEP, VICTIM_REL);
        AnimalWeightAttachment.set(sheep, 4);

        ItemEntity mutton = newDrop(helper, VICTIM_REL, Items.MUTTON, 1);
        ItemEntity wool = newDrop(helper, VICTIM_REL, Items.WHITE_WOOL, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(mutton, wool));
        postDropsEventGeneric(helper, sheep, drops);

        if (mutton.getItem().getCount() != 4) {
            helper.fail("weight-4 sheep: mutton expected 1 * 4 = 4; got " + mutton.getItem().getCount());
            return;
        }
        if (wool.getItem().getCount() != 4) {
            helper.fail("weight-4 sheep: white_wool expected 1 * 4 = 4; got " + wool.getItem().getCount()
                    + " — ItemTags.WOOL may not cover white wool");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-4 sheep (red wool — pre-coloured) → mutton * 4,
     * red-wool stack * 4". Pins that {@code ItemTags.WOOL} covers all dye
     * colours, not just white.
     */
    public static void killWeightFourSheepDropsThreeExtraRedWool(GameTestHelper helper) {
        Sheep sheep = helper.spawnWithNoFreeWill(EntityType.SHEEP, VICTIM_REL);
        AnimalWeightAttachment.set(sheep, 4);

        ItemEntity wool = newDrop(helper, VICTIM_REL, Items.RED_WOOL, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(wool));
        postDropsEventGeneric(helper, sheep, drops);

        if (wool.getItem().getCount() != 4) {
            helper.fail("weight-4 sheep: red_wool expected 1 * 4 = 4; got " + wool.getItem().getCount()
                    + " — ItemTags.WOOL may only cover white wool (dye-coverage bug)");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-4 cow with fire-damage death → cooked beef * 4,
     * leather * 4". Pins that {@link Items#COOKED_BEEF} is a recognised
     * primary drop. Bug it catches: the handler only checking raw BEEF, so
     * a torch-spammed pen produces undifferentiated cooked drops.
     */
    public static void killWeightFourCowFireDropsCookedBeefPlusThree(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 4);

        ItemEntity cooked = newDrop(helper, VICTIM_REL, Items.COOKED_BEEF, 2);
        ItemEntity leather = newDrop(helper, VICTIM_REL, Items.LEATHER, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(cooked, leather));
        // Use in-fire damage source for parity with the spec's fire-kill case.
        postDropsEvent(cow, drops, helper.getLevel().damageSources().inFire());

        if (cooked.getItem().getCount() != 8) {
            helper.fail("weight-4 cow (fire kill): cooked_beef expected 2 * 4 = 8; got "
                    + cooked.getItem().getCount() + " — COOKED_BEEF not in primary drop set");
            return;
        }
        if (leather.getItem().getCount() != 4) {
            helper.fail("weight-4 cow (fire kill): leather expected 1 * 4 = 4; got " + leather.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-8 zombie → vanilla zombie drops, no bonus". Pins
     * the over-broad-target-filter bug: the handler's {@code isTargetSpecies}
     * must reject non-Cow/Pig/Sheep entities entirely. Weight on a zombie is
     * nonsensical but the attachment isn't filtered — so the handler is the
     * only line of defence.
     */
    public static void killWeightEightZombieUnaffected(GameTestHelper helper) {
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, VICTIM_REL);
        AnimalWeightAttachment.set(zombie, 8);

        ItemEntity rottenFlesh = newDrop(helper, VICTIM_REL, Items.ROTTEN_FLESH, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(rottenFlesh));
        postDropsEventGeneric(helper, zombie, drops);

        if (rottenFlesh.getItem().getCount() != 1) {
            helper.fail("weight-8 zombie: rotten_flesh expected 1 (zombie not a target); got "
                    + rottenFlesh.getItem().getCount() + " — handler over-broadly matched non-farm animal");
            return;
        }

        // Also verify the XP path doesn't fire for zombie.
        LivingExperienceDropEvent xpEvent = new LivingExperienceDropEvent(zombie, null, /* base */ 5);
        NeoForge.EVENT_BUS.post(xpEvent);
        if (xpEvent.getDroppedExperience() != 5) {
            helper.fail("weight-8 zombie: dropped XP expected 5 (unchanged); got " + xpEvent.getDroppedExperience()
                    + " — XP path also over-broadly matched non-farm animal");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-1 cow → XP unchanged". Pins the XP guard at the
     * default weight — bonus is 0, base XP returned unchanged.
     */
    public static void killWeightOneCowXpUnchanged(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 1);

        LivingExperienceDropEvent xpEvent = new LivingExperienceDropEvent(cow, null, /* base */ 3);
        NeoForge.EVENT_BUS.post(xpEvent);

        if (xpEvent.getDroppedExperience() != 3) {
            helper.fail("weight-1 cow: XP expected 3 (no bonus); got " + xpEvent.getDroppedExperience()
                    + " — XP bonus accidentally fires at weight 1");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "kill weight-4 cow → XP * 4". Pins that
     * {@link DropScalingHandler#onLivingExperienceDrop} multiplies dropped XP
     * by the weight.
     */
    public static void killWeightFourCowXpGetsThreeBonus(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 4);

        LivingExperienceDropEvent xpEvent = new LivingExperienceDropEvent(cow, null, /* base */ 3);
        NeoForge.EVENT_BUS.post(xpEvent);

        if (xpEvent.getDroppedExperience() != 12) {
            helper.fail("weight-4 cow: XP expected 3 * 4 = 12; got " + xpEvent.getDroppedExperience());
            return;
        }
        helper.succeed();
    }
}
