package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
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
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Bodies for Tier-2 GameTests covering the accelerating drop-bonus curve.
 * Spec source: run-004 {@code design.md} section H and the curve table in
 * {@link AnimalWeightsTuning#DROP_BONUS_BY_WEIGHT}:
 *
 * <pre>
 *   w=1 →  0    w=4 →  +3    w=7 →  +8
 *   w=2 →  +1   w=5 →  +4    w=8 → +11
 *   w=3 →  +2   w=6 →  +6
 * </pre>
 *
 * <p>Linear through w=5, then accelerates: w=6 gains +1 over linear, w=7
 * gains +2, w=8 gains +4. Existing run-002/003 tests cover w=4 and w=8 (the
 * w=8 test was originally written for the linear formula); this run pins the
 * three accelerating tiers (w=6, w=7, w=8) end-to-end against the curve
 * table and verifies XP follows the same curve at the peak.
 *
 * <p>Bugs these tests catch:
 * <ul>
 *   <li>A linear-only handler ({@code bonus = max(0, weight - 1)}) would
 *       fail w=6 (expected +6, gives +5), w=7 (expected +8, gives +6), and
 *       w=8 (expected +11, gives +7).</li>
 *   <li>An XP path that uses a different table from drops would diverge at
 *       weights 6+ — the spec says XP uses the SAME curve.</li>
 *   <li>An off-by-one in the {@code DROP_BONUS_BY_WEIGHT} array.</li>
 * </ul>
 */
public final class DropCurveGameTests {

    private DropCurveGameTests() {}

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
     * Spec row H: "Kill weight-6 cow → +6 extra beef." Pins the first
     * curve-deviation tier: at w=6 the bonus is +6 instead of the linear +5.
     */
    public static void killWeightSixCowDropsSixExtraBeef(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 6);

        ItemEntity beef = newDrop(helper, Items.BEEF, 2);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef));
        postDropsEventGeneric(helper, cow, drops);

        // Expect 2 + 6 = 8. Linear would give 2 + 5 = 7.
        if (beef.getItem().getCount() != 8) {
            helper.fail("weight-6 cow: beef expected 2 + 6 = 8 (curve table); got "
                    + beef.getItem().getCount()
                    + " — handler may be using linear (weight - 1) instead of DROP_BONUS_BY_WEIGHT[6]=+6");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row H: "Kill weight-7 cow → +8 extra beef." Curve gains +2 over
     * linear at w=7.
     */
    public static void killWeightSevenCowDropsEightExtraBeef(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 7);

        ItemEntity beef = newDrop(helper, Items.BEEF, 2);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef));
        postDropsEventGeneric(helper, cow, drops);

        // Expect 2 + 8 = 10. Linear would give 2 + 6 = 8.
        if (beef.getItem().getCount() != 10) {
            helper.fail("weight-7 cow: beef expected 2 + 8 = 10 (curve table); got "
                    + beef.getItem().getCount()
                    + " — handler may be using linear or DROP_BONUS_BY_WEIGHT[7] is wrong");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row H: "Kill weight-8 cow → +11 extra beef." Peak of the curve;
     * +4 over linear.
     */
    public static void killWeightEightCowDropsElevenExtraBeef(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 8);

        ItemEntity beef = newDrop(helper, Items.BEEF, 2);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef));
        postDropsEventGeneric(helper, cow, drops);

        // Expect 2 + 11 = 13. Linear would give 2 + 7 = 9.
        if (beef.getItem().getCount() != 13) {
            helper.fail("weight-8 cow: beef expected 2 + 11 = 13 (peak of curve); got "
                    + beef.getItem().getCount()
                    + " — handler may be using linear or DROP_BONUS_BY_WEIGHT[8] is wrong");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row H: "XP at weight 8 uses the same curve." Pins that
     * {@link DropScalingHandler#onLivingExperienceDrop} reads from the same
     * {@code DROP_BONUS_BY_WEIGHT} table as the drop path; without that
     * coupling, XP could diverge from drops at w&ge;6.
     *
     * <p>Spawn weight-8 cow, post a {@link LivingExperienceDropEvent} with
     * base XP = 5 (vanilla cow base). Expect 5 + 11 = 16.
     */
    public static void xpAtWeightEightUsesSameCurve(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 8);

        LivingExperienceDropEvent xpEvent = new LivingExperienceDropEvent(cow, null, /* base */ 5);
        NeoForge.EVENT_BUS.post(xpEvent);

        // Expect 5 + 11 = 16. Linear would give 5 + 7 = 12.
        if (xpEvent.getDroppedExperience() != 16) {
            helper.fail("weight-8 cow: XP expected 5 + 11 = 16 (curve peak); got "
                    + xpEvent.getDroppedExperience()
                    + " — XP path may not use the same DROP_BONUS_BY_WEIGHT curve as drops, or "
                    + "is still using the old linear formula");
            return;
        }
        helper.succeed();
    }
}
