package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.event.DropScalingHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Bodies for Tier-2 GameTests covering the sick-mob drop reduction. Spec
 * source: run-004 {@code design.md} section E.
 *
 * <p>The production handler {@link DropScalingHandler#onLivingDrops} branches
 * on weight 0: instead of applying the {@code DROP_BONUS_BY_WEIGHT} curve, it
 * iterates each primary drop and either removes the {@link ItemEntity}
 * entirely (with {@code SICK_DROP_REMOVAL_CHANCE}) or caps its stack count to
 * 1. Non-primary drops are untouched.
 *
 * <p>This file covers the CAP-TO-ONE invariant. The probabilistic removal
 * case is intentionally skipped (per design.md it's hard to test
 * deterministically without injecting a controlled RNG, and the cap covers
 * the important production invariant).
 */
public final class SickDropReductionGameTests {

    private SickDropReductionGameTests() {}

    private static final BlockPos VICTIM_REL = new BlockPos(2, 2, 2);

    private static ItemEntity newDrop(GameTestHelper helper, Item item, int count) {
        BlockPos abs = helper.absolutePos(VICTIM_REL);
        return new ItemEntity(helper.getLevel(),
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                new ItemStack(item, count));
    }

    private static void postDropsEventGeneric(GameTestHelper helper, LivingEntity victim, List<ItemEntity> drops) {
        LivingDropsEvent event = new LivingDropsEvent(victim, helper.getLevel().damageSources().generic(),
                drops, /* recentlyHit */ true);
        NeoForge.EVENT_BUS.post(event);
    }

    /**
     * Spec row E: "Kill a weight-0 cow. For each primary drop in
     * event.getDrops(), assert stack count &le; 1."
     *
     * <p>Setup: post a LivingDropsEvent with two primary drops at oversized
     * counts (BEEF×8, LEATHER×8) and one non-primary drop (BONE×8 — bones are
     * NOT in the cow primary drop set). After the handler runs:
     * <ul>
     *   <li>Every primary drop that REMAINS in the event's drop list must
     *       have count &le; 1 (cap invariant).</li>
     *   <li>The non-primary drop is untouched (count stays at 8).</li>
     * </ul>
     *
     * <p>Bug it catches: the cap logic missing entirely (sick cows drop full
     * stacks), or applying to non-primary drops too (over-broad cull).
     *
     * <p>The probabilistic removal is permitted to fire on either primary
     * drop — the test does not require BOTH primaries to survive, only that
     * each SURVIVING primary has count &le; 1.
     */
    public static void sickCowDropsCappedToOnePerPrimary(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 0);

        ItemEntity beef = newDrop(helper, Items.BEEF, 8);
        ItemEntity leather = newDrop(helper, Items.LEATHER, 8);
        ItemEntity bone = newDrop(helper, Items.BONE, 8); // non-primary control
        List<ItemEntity> drops = new ArrayList<>(List.of(beef, leather, bone));
        postDropsEventGeneric(helper, cow, drops);

        // Each surviving drop in the event's drop list must satisfy:
        // - if it's beef or leather (primary), count <= 1
        // - if it's bone (non-primary), count == 8 (untouched)
        for (ItemEntity drop : drops) {
            ItemStack stack = drop.getItem();
            int count = stack.getCount();
            if (stack.is(Items.BEEF) || stack.is(Items.LEATHER)) {
                if (count > 1) {
                    helper.fail("weight-0 cow: primary drop " + stack.getItem() + " has count "
                            + count + " (expected <= 1) — cap-to-1 invariant is broken");
                    return;
                }
            } else if (stack.is(Items.BONE)) {
                if (count != 8) {
                    helper.fail("weight-0 cow: non-primary drop BONE has count " + count
                            + " (expected 8, untouched) — sick reduction is over-broadly hitting "
                            + "non-primary drops");
                    return;
                }
            } else {
                helper.fail("weight-0 cow: unexpected drop in event list: " + stack.getItem());
                return;
            }
        }
        // The bone must still be present (not accidentally removed).
        boolean boneStillPresent = false;
        for (ItemEntity drop : drops) {
            if (drop.getItem().is(Items.BONE)) {
                boneStillPresent = true;
                break;
            }
        }
        if (!boneStillPresent) {
            helper.fail("weight-0 cow: non-primary drop BONE was removed from the event — "
                    + "sick reduction is over-broadly removing non-primary drops");
            return;
        }
        helper.succeed();
    }
}
