package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.event.DailyEvalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier-2 GameTests for Mooshroom coverage, asserting the spec rows in run-003
 * {@code design.md} "Mooshroom" coverage targets.
 *
 * <p><b>Critical:</b> in MC 26.1 {@link MushroomCow} extends
 * {@link net.minecraft.world.entity.animal.cow.AbstractCow}, NOT
 * {@link net.minecraft.world.entity.animal.cow.Cow}. The two are siblings.
 * Handlers must use {@code instanceof AbstractCow} (or check both
 * explicitly) to cover both species. These tests assert that the post-run-003
 * refactor truly works for Mooshroom — if any of them fail, the corresponding
 * handler still uses a tighter type check than {@code AbstractCow}.
 *
 * <p>Mooshroom shares Cow's vanilla loot table (beef + leather), so the
 * scaling-eligible primary drops are the same as Cow's. There is no
 * Mooshroom-specific extra drop here — mushroom-stew interaction is an alive-
 * mob surface and is explicitly out of scope per design.md.
 */
public final class MooshroomGameTests {

    private MooshroomGameTests() {}

    /**
     * Per-class isolated-position counter. Offset from cow tests (100_000),
     * chicken tests (200_000), and rabbit tests (300_000); mooshroom uses
     * 400_000 base X.
     */
    private static final AtomicInteger ISOLATION_X = new AtomicInteger(0);

    private static final int ISOLATION_Y = 128;
    private static final int ISOLATION_BASE_X = 400_000;
    private static final int ISOLATION_STRIDE = 1000;

    private static final BlockPos VICTIM_REL = new BlockPos(2, 2, 2);

    private static BlockPos nextIsolatedPos() {
        int n = ISOLATION_X.getAndIncrement();
        return new BlockPos(ISOLATION_BASE_X + n * ISOLATION_STRIDE, ISOLATION_Y, 0);
    }

    private static void setAbsBlock(ServerLevel level, BlockPos abs, BlockState state) {
        level.setBlock(abs, state, 3);
    }

    /**
     * Build a "perfect pen" (all four conditions satisfied) around
     * {@code center}, calibrated for the BFS reachability spec from run-004.
     * See {@link DailyEvalGameTests#buildPerfectPen} for the design rationale.
     */
    private static void buildPerfectPen(ServerLevel level, BlockPos center) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                setAbsBlock(level, center.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        setAbsBlock(level, center.offset(-1, -1, 0), Blocks.WATER_CAULDRON.defaultBlockState());
        setAbsBlock(level, center.offset(3, -1, 0), Blocks.WATER_CAULDRON.defaultBlockState());
        setAbsBlock(level, center.above(), Blocks.TORCH.defaultBlockState());
    }

    private static ItemEntity newDrop(GameTestHelper helper, BlockPos rel, net.minecraft.world.item.Item item, int count) {
        BlockPos abs = helper.absolutePos(rel);
        return new ItemEntity(helper.getLevel(),
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                new ItemStack(item, count));
    }

    // ----------------------------------------------------------------------
    // Daily evaluation
    // ----------------------------------------------------------------------

    /**
     * Spec: "mooshroom_in_perfect_pen_weight_one_to_two" — confirms Mooshroom
     * inherits Cow's daily-eval coverage via {@code AbstractCow}. Bug it
     * catches: handler uses {@code instanceof Cow} instead of
     * {@code instanceof AbstractCow}, leaving Mooshroom completely unmanaged.
     */
    public static void mooshroomInPerfectPenWeightOneToTwo(GameTestHelper helper) {
        MushroomCow mooshroom = helper.spawnWithNoFreeWill(EntityType.MOOSHROOM, VICTIM_REL);
        BlockPos pen = nextIsolatedPos();

        helper.runAfterDelay(2L, () -> {
            ServerLevel level = helper.getLevel();
            mooshroom.snapTo(pen.getX() + 0.5, (double) pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            buildPerfectPen(level, pen);
            AnimalWeightAttachment.set(mooshroom, 1);
            DailyEvalHandler.runEvaluation(level);
            int weight = AnimalWeightAttachment.get(mooshroom);
            if (weight != 2) {
                helper.fail("weight-1 mooshroom in perfect pen expected 2 after eval; got "
                        + weight + " — DailyEvalHandler may still use instanceof Cow instead of AbstractCow; "
                        + "Mooshroom is NOT a subclass of Cow in MC 26.1, only a sibling under AbstractCow");
                return;
            }
            helper.succeed();
        });
    }

    // ----------------------------------------------------------------------
    // Drop scaling
    // ----------------------------------------------------------------------

    /**
     * Spec: "kill_weight_four_mooshroom_drops_three_extra_beef_and_leather" —
     * Mooshroom shares Cow's primary drop set; at weight 4, beef + 3 and
     * leather + 3. Bug it catches: handler uses {@code instanceof Cow},
     * dropping Mooshroom kills back to vanilla counts.
     */
    public static void killWeightFourMooshroomDropsThreeExtraBeefAndLeather(GameTestHelper helper) {
        MushroomCow mooshroom = helper.spawnWithNoFreeWill(EntityType.MOOSHROOM, VICTIM_REL);
        AnimalWeightAttachment.set(mooshroom, 4);

        ItemEntity beef = newDrop(helper, VICTIM_REL, Items.BEEF, 2);
        ItemEntity leather = newDrop(helper, VICTIM_REL, Items.LEATHER, 1);
        List<ItemEntity> drops = new ArrayList<>(List.of(beef, leather));
        NeoForge.EVENT_BUS.post(new LivingDropsEvent(mooshroom,
                helper.getLevel().damageSources().generic(), drops, true));

        if (beef.getItem().getCount() != 5) {
            helper.fail("weight-4 mooshroom: beef expected 2 + 3 = 5; got "
                    + beef.getItem().getCount() + " — DropScalingHandler may still gate on instanceof Cow; "
                    + "Mooshroom is a sibling not subclass in MC 26.1");
            return;
        }
        if (leather.getItem().getCount() != 4) {
            helper.fail("weight-4 mooshroom: leather expected 1 + 3 = 4; got "
                    + leather.getItem().getCount());
            return;
        }
        helper.succeed();
    }

    // ----------------------------------------------------------------------
    // Sick state
    // ----------------------------------------------------------------------

    /**
     * Spec: "weight_zero_mooshroom_has_slowness" — weight-0 mooshroom gets
     * SLOWNESS within 2 ticks. Bug it catches: handler uses
     * {@code instanceof Cow} for sick-state filter, leaving mooshrooms able to
     * become weight 0 but never visibly slowed (and breeding-when-sick).
     */
    public static void weightZeroMooshroomHasSlowness(GameTestHelper helper) {
        MushroomCow mooshroom = helper.spawnWithNoFreeWill(EntityType.MOOSHROOM, VICTIM_REL);
        mooshroom.setNoGravity(true);
        AnimalWeightAttachment.set(mooshroom, 0);

        helper.succeedWhen(() -> {
            MobEffectInstance effect = mooshroom.getEffect(MobEffects.SLOWNESS);
            if (effect == null) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 mooshroom has no SLOWNESS yet — "
                                        + "SickStateHandler may still use instanceof Cow"),
                        0);
            }
            if (effect.getAmplifier() != 0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        net.minecraft.network.chat.Component.literal(
                                "weight-0 mooshroom SLOWNESS amplifier expected 0; got "
                                        + effect.getAmplifier()),
                        0);
            }
        });
    }
}
