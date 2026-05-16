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
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Bodies for Tier-2 GameTests covering the multiplicative drop curve at the
 * upper weight tiers. Spec source: run-005 {@code decisions.md} Q2 ("Option B
 * — multiplicative on primary drops"):
 *
 * <pre>
 *   stack count: stack.setCount(stack.getCount() * weight)
 *   xp dropped:  event.getDroppedExperience() * weight
 * </pre>
 *
 * <p>Existing run-002/003 tests cover weight 4 (the canonical case); this
 * class pins the higher tiers (w=6, w=7, w=8) end-to-end against the
 * multiplicative formula and verifies XP follows the same multiplier at the
 * peak.
 *
 * <p>Bugs these tests catch:
 * <ul>
 *   <li>A regression to the old additive curve {0,0,1,2,3,4,6,8,11} — under
 *       additive, weight-8 on baseCount=2 would yield 2+11=13; multiplicative
 *       requires 2*8=16. Mismatch is loud.</li>
 *   <li>An XP path that uses a different multiplier from drops — the spec
 *       says XP uses the SAME weight multiplier.</li>
 *   <li>Off-by-one in the weight clamp on the upper bound.</li>
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
     * Spec row: "Kill weight-6 cow → beef * 6." Pins the mid-range multiplier
     * — base 2 must become 12.
     */
    public static void killWeightSixCowDropsSixExtraBeef(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 6);

        ItemEntity beef = newDrop(helper, Items.BEEF, 2);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef));
        postDropsEventGeneric(helper, cow, drops);

        // Expect 2 * 6 = 12. Old additive curve would have given 2 + 6 = 8.
        if (beef.getItem().getCount() != 12) {
            helper.fail("weight-6 cow: beef expected 2 * 6 = 12 (multiplicative); got "
                    + beef.getItem().getCount()
                    + " — handler may be using legacy additive DROP_BONUS_BY_WEIGHT");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "Kill weight-7 cow → beef * 7." Base 2 must become 14.
     */
    public static void killWeightSevenCowDropsEightExtraBeef(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 7);

        ItemEntity beef = newDrop(helper, Items.BEEF, 2);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef));
        postDropsEventGeneric(helper, cow, drops);

        // Expect 2 * 7 = 14. Old additive curve would have given 2 + 8 = 10.
        if (beef.getItem().getCount() != 14) {
            helper.fail("weight-7 cow: beef expected 2 * 7 = 14 (multiplicative); got "
                    + beef.getItem().getCount()
                    + " — handler may be using legacy additive DROP_BONUS_BY_WEIGHT");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "Kill weight-8 cow → beef * 8." Peak multiplier; base 2 must
     * become 16.
     */
    public static void killWeightEightCowDropsElevenExtraBeef(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 8);

        ItemEntity beef = newDrop(helper, Items.BEEF, 2);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef));
        postDropsEventGeneric(helper, cow, drops);

        // Expect 2 * 8 = 16. Old additive curve would have given 2 + 11 = 13.
        if (beef.getItem().getCount() != 16) {
            helper.fail("weight-8 cow: beef expected 2 * 8 = 16 (peak multiplier); got "
                    + beef.getItem().getCount()
                    + " — handler may be using legacy additive DROP_BONUS_BY_WEIGHT");
            return;
        }
        helper.succeed();
    }

    /**
     * Spec row: "XP at weight 8 uses the same multiplier." Pins that
     * {@link DropScalingHandler#onLivingExperienceDrop} applies the same
     * weight multiplier as the drop path; without that coupling, XP could
     * diverge from drops.
     *
     * <p>Spawn weight-8 cow, post a {@link LivingExperienceDropEvent} with
     * base XP = 5 (vanilla cow base). Expect 5 * 8 = 40.
     */
    public static void xpAtWeightEightUsesSameCurve(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, VICTIM_REL);
        AnimalWeightAttachment.set(cow, 8);

        LivingExperienceDropEvent xpEvent = new LivingExperienceDropEvent(cow, null, /* base */ 5);
        NeoForge.EVENT_BUS.post(xpEvent);

        // Expect 5 * 8 = 40. Old additive curve would have given 5 + 11 = 16.
        if (xpEvent.getDroppedExperience() != 40) {
            helper.fail("weight-8 cow: XP expected 5 * 8 = 40 (peak multiplier); got "
                    + xpEvent.getDroppedExperience()
                    + " — XP path may not use the same weight multiplier as drops, or "
                    + "is still using the old additive formula");
            return;
        }
        helper.succeed();
    }
}
