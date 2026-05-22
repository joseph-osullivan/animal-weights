package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeights;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * Tier-2 GameTest registration aggregator for Animal Weights.
 *
 * <p>One {@link DeferredHolder} per registered test function, paired with a
 * {@code test_instance} JSON at
 * {@code src/main/resources/data/animalweights/test_instance/<name>.json}.
 * Test bodies live in per-handler sibling classes ({@link WeightAttachmentGameTests},
 * {@link DailyEvalGameTests}, {@link DropScalingGameTests},
 * {@link SickStateGameTests}). This file is the single source of truth for
 * what's registered and what function ids the {@code test_instance} JSON files
 * must reference.
 *
 * <p>Discovery model (NeoForge 26.1):
 * <pre>
 *   Test function (here) ←→ test_instance JSON ←→ structure template
 * </pre>
 * Most tests use the built-in {@code minecraft:empty} structure (an empty
 * 1×1×1 air room with no floor). Pens are built programmatically inside the
 * test body with {@code helper.setBlock(...)}.
 *
 * <p>Run via {@code ./gradlew runGameTestServer}; full pre-merge composite is
 * {@code ./gradlew integrationCheck} (wired in {@code build.gradle}).
 *
 * <p>Mirrors the {@code com.lordoflands.gametest.ModGameTests} pattern; see
 * that file for an example with many more registrations.
 */
public final class ModGameTests {

    private ModGameTests() {
        // utility class — no instances
    }

    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, AnimalWeights.MOD_ID);

    /** Bind the registry onto the mod event bus. Called once from {@link AnimalWeights}. */
    public static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }

    // ----------------------------------------------------------------------
    // Wiring sanity test — always passes. If this fails, the
    // DeferredRegister<TestFunction> + test_instance JSON pipeline is broken.
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SANITY =
            TEST_FUNCTIONS.register("sanity", () -> GameTestHelper::succeed);

    // ----------------------------------------------------------------------
    // AnimalWeightAttachment suite
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_DEFAULT_FOR_FRESH_COW = TEST_FUNCTIONS.register(
            "weight_default_for_fresh_cow",
            () -> WeightAttachmentGameTests::weightDefaultForFreshCow);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_SET_CLAMPS_NEGATIVE_TO_ZERO = TEST_FUNCTIONS.register(
            "weight_set_clamps_negative_to_zero",
            () -> WeightAttachmentGameTests::weightSetClampsNegativeToZero);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_SET_CLAMPS_OVERFLOW_TO_MAX = TEST_FUNCTIONS.register(
            "weight_set_clamps_overflow_to_max",
            () -> WeightAttachmentGameTests::weightSetClampsOverflowToMax);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_PERSISTS_THROUGH_NBT_SAVE_LOAD = TEST_FUNCTIONS.register(
            "weight_persists_through_nbt_save_load",
            () -> WeightAttachmentGameTests::weightPersistsThroughNbtSaveLoad);

    // ----------------------------------------------------------------------
    // DailyEvalHandler suite — design.md "Tests that catch real bugs" table
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ONE_COW_IN_PERFECT_PEN_INCREASES_TO_TWO = TEST_FUNCTIONS.register(
            "weight_one_cow_in_perfect_pen_increases_to_two",
            () -> DailyEvalGameTests::weightOneCowInPerfectPenIncreasesToTwo);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ONE_COW_WITH_THREE_CONDITIONS_STAYS_AT_ONE = TEST_FUNCTIONS.register(
            "weight_one_cow_with_three_conditions_stays_at_one",
            () -> DailyEvalGameTests::weightOneCowWithThreeConditionsStaysAtOne);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ONE_COW_WITH_ZERO_CONDITIONS_DECREASES_TO_ZERO = TEST_FUNCTIONS.register(
            "weight_one_cow_with_zero_conditions_decreases_to_zero",
            () -> DailyEvalGameTests::weightOneCowWithZeroConditionsDecreasesToZero);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ONE_COW_WITH_ONE_CONDITION_DECREASES_TO_ZERO = TEST_FUNCTIONS.register(
            "weight_one_cow_with_one_condition_decreases_to_zero",
            () -> DailyEvalGameTests::weightOneCowWithOneConditionDecreasesToZero);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_EIGHT_COW_IN_PERFECT_PEN_STAYS_AT_EIGHT = TEST_FUNCTIONS.register(
            "weight_eight_cow_in_perfect_pen_stays_at_eight",
            () -> DailyEvalGameTests::weightEightCowInPerfectPenStaysAtEight);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_COW_IN_PERFECT_PEN_INCREASES_TO_ONE = TEST_FUNCTIONS.register(
            "weight_zero_cow_in_perfect_pen_increases_to_one",
            () -> DailyEvalGameTests::weightZeroCowInPerfectPenIncreasesToOne);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_NEXT_TO_CHICKEN_LOSES_STRETCHING = TEST_FUNCTIONS.register(
            "cow_next_to_chicken_loses_stretching",
            () -> DailyEvalGameTests::cowNextToChickenLosesStretching);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_NEXT_TO_PIG_LOSES_STRETCHING = TEST_FUNCTIONS.register(
            "cow_next_to_pig_loses_stretching",
            () -> DailyEvalGameTests::cowNextToPigLosesStretching);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_NEXT_TO_ZOMBIE_KEEPS_STRETCHING = TEST_FUNCTIONS.register(
            "cow_next_to_zombie_keeps_stretching",
            () -> DailyEvalGameTests::cowNextToZombieKeepsStretching);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            FLOWING_WATER_DOES_NOT_COUNT_AS_SOURCE = TEST_FUNCTIONS.register(
            "flowing_water_does_not_count_as_source",
            () -> DailyEvalGameTests::flowingWaterDoesNotCountAsSource);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            TWO_ADJACENT_COWS_BOTH_FAIL_STRETCHING = TEST_FUNCTIONS.register(
            "two_adjacent_cows_both_fail_stretching",
            () -> DailyEvalGameTests::twoAdjacentCowsBothFailStretching);

    // ----------------------------------------------------------------------
    // DropScalingHandler suite
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ONE_COW_DROPS_VANILLA_COUNTS = TEST_FUNCTIONS.register(
            "kill_weight_one_cow_drops_vanilla_counts",
            () -> DropScalingGameTests::killWeightOneCowDropsVanillaCounts);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_COW_DROPS_THREE_EXTRA = TEST_FUNCTIONS.register(
            "kill_weight_four_cow_drops_three_extra",
            () -> DropScalingGameTests::killWeightFourCowDropsThreeExtra);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ZERO_COW_DROPS_VANILLA_NO_SHRINK = TEST_FUNCTIONS.register(
            "kill_weight_zero_cow_drops_vanilla_no_shrink",
            () -> DropScalingGameTests::killWeightZeroCowDropsVanillaNoShrink);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_PIG_DROPS_THREE_EXTRA_PORK = TEST_FUNCTIONS.register(
            "kill_weight_four_pig_drops_three_extra_pork",
            () -> DropScalingGameTests::killWeightFourPigDropsThreeExtraPork);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_SHEEP_DROPS_THREE_EXTRA_MUTTON_AND_WOOL = TEST_FUNCTIONS.register(
            "kill_weight_four_sheep_drops_three_extra_mutton_and_wool",
            () -> DropScalingGameTests::killWeightFourSheepDropsThreeExtraMuttonAndWool);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_SHEEP_DROPS_THREE_EXTRA_RED_WOOL = TEST_FUNCTIONS.register(
            "kill_weight_four_sheep_drops_three_extra_red_wool",
            () -> DropScalingGameTests::killWeightFourSheepDropsThreeExtraRedWool);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_COW_FIRE_DROPS_COOKED_BEEF_PLUS_THREE = TEST_FUNCTIONS.register(
            "kill_weight_four_cow_fire_drops_cooked_beef_plus_three",
            () -> DropScalingGameTests::killWeightFourCowFireDropsCookedBeefPlusThree);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_EIGHT_ZOMBIE_UNAFFECTED = TEST_FUNCTIONS.register(
            "kill_weight_eight_zombie_unaffected",
            () -> DropScalingGameTests::killWeightEightZombieUnaffected);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ONE_COW_XP_UNCHANGED = TEST_FUNCTIONS.register(
            "kill_weight_one_cow_xp_unchanged",
            () -> DropScalingGameTests::killWeightOneCowXpUnchanged);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_COW_XP_GETS_THREE_BONUS = TEST_FUNCTIONS.register(
            "kill_weight_four_cow_xp_gets_three_bonus",
            () -> DropScalingGameTests::killWeightFourCowXpGetsThreeBonus);

    // ----------------------------------------------------------------------
    // SickStateHandler suite
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_COW_GETS_SLOWNESS_AFTER_ONE_TICK = TEST_FUNCTIONS.register(
            "weight_zero_cow_gets_slowness_after_one_tick",
            () -> SickStateGameTests::weightZeroCowGetsSlownessAfterOneTick);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_COW_KEEPS_SLOWNESS_AFTER_SIXTY_TICKS = TEST_FUNCTIONS.register(
            "weight_zero_cow_keeps_slowness_after_sixty_ticks",
            () -> SickStateGameTests::weightZeroCowKeepsSlownessAfterSixtyTicks);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ONE_COW_HAS_NO_SLOWNESS = TEST_FUNCTIONS.register(
            "weight_one_cow_has_no_slowness",
            () -> SickStateGameTests::weightOneCowHasNoSlowness);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_COW_PAIR_BREED_CANCELLED_AND_BOTH_RESET = TEST_FUNCTIONS.register(
            "weight_zero_cow_pair_breed_cancelled_and_both_reset",
            () -> SickStateGameTests::weightZeroCowPairBreedCancelledAndBothReset);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MIXED_WEIGHT_COW_PAIR_BREED_CANCELLED = TEST_FUNCTIONS.register(
            "mixed_weight_cow_pair_breed_cancelled",
            () -> SickStateGameTests::mixedWeightCowPairBreedCancelled);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            HEALTHY_COW_PAIR_BREED_NOT_CANCELLED = TEST_FUNCTIONS.register(
            "healthy_cow_pair_breed_not_cancelled",
            () -> SickStateGameTests::healthyCowPairBreedNotCancelled);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_ZOMBIE_NOT_AFFECTED_BY_SLOWNESS = TEST_FUNCTIONS.register(
            "weight_zero_zombie_not_affected_by_slowness",
            () -> SickStateGameTests::weightZeroZombieNotAffectedBySlowness);

    // ----------------------------------------------------------------------
    // Run-003: Chicken suite — design.md "Chicken" coverage targets
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CHICKEN_IN_PERFECT_PEN_WEIGHT_ONE_TO_TWO = TEST_FUNCTIONS.register(
            "chicken_in_perfect_pen_weight_one_to_two",
            () -> ChickenGameTests::chickenInPerfectPenWeightOneToTwo);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CHICKEN_IN_CROWDED_PEN_DECREASES_WEIGHT = TEST_FUNCTIONS.register(
            "chicken_in_crowded_pen_decreases_weight",
            () -> ChickenGameTests::chickenInCrowdedPenDecreasesWeight);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CHICKEN_NEXT_TO_COW_LOSES_STRETCHING = TEST_FUNCTIONS.register(
            "chicken_next_to_cow_loses_stretching",
            () -> ChickenGameTests::chickenNextToCowLosesStretching);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CHICKEN_NEXT_TO_RABBIT_LOSES_STRETCHING = TEST_FUNCTIONS.register(
            "chicken_next_to_rabbit_loses_stretching",
            () -> ChickenGameTests::chickenNextToRabbitLosesStretching);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ONE_CHICKEN_DROPS_BASELINE = TEST_FUNCTIONS.register(
            "kill_weight_one_chicken_drops_baseline",
            () -> ChickenGameTests::killWeightOneChickenDropsBaseline);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_CHICKEN_DROPS_THREE_EXTRA_RAW_CHICKEN_AND_FEATHER = TEST_FUNCTIONS.register(
            "kill_weight_four_chicken_drops_three_extra_raw_chicken_and_feather",
            () -> ChickenGameTests::killWeightFourChickenDropsThreeExtraRawChickenAndFeather);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_EIGHT_CHICKEN_DROPS_SEVEN_EXTRA = TEST_FUNCTIONS.register(
            "kill_weight_eight_chicken_drops_seven_extra",
            () -> ChickenGameTests::killWeightEightChickenDropsSevenExtra);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_CHICKEN_HAS_SLOWNESS = TEST_FUNCTIONS.register(
            "weight_zero_chicken_has_slowness",
            () -> ChickenGameTests::weightZeroChickenHasSlowness);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_CHICKEN_CANNOT_BREED = TEST_FUNCTIONS.register(
            "weight_zero_chicken_cannot_breed",
            () -> ChickenGameTests::weightZeroChickenCannotBreed);

    // ----------------------------------------------------------------------
    // Run-003: Rabbit suite — design.md "Rabbit" coverage targets
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            RABBIT_IN_PERFECT_PEN_WEIGHT_ONE_TO_TWO = TEST_FUNCTIONS.register(
            "rabbit_in_perfect_pen_weight_one_to_two",
            () -> RabbitGameTests::rabbitInPerfectPenWeightOneToTwo);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            RABBIT_IN_CROWDED_PEN_DECREASES_WEIGHT = TEST_FUNCTIONS.register(
            "rabbit_in_crowded_pen_decreases_weight",
            () -> RabbitGameTests::rabbitInCrowdedPenDecreasesWeight);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            RABBIT_NEXT_TO_CHICKEN_LOSES_STRETCHING = TEST_FUNCTIONS.register(
            "rabbit_next_to_chicken_loses_stretching",
            () -> RabbitGameTests::rabbitNextToChickenLosesStretching);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ONE_RABBIT_DROPS_BASELINE = TEST_FUNCTIONS.register(
            "kill_weight_one_rabbit_drops_baseline",
            () -> RabbitGameTests::killWeightOneRabbitDropsBaseline);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_RABBIT_DROPS_THREE_EXTRA_RAW_RABBIT_AND_HIDE = TEST_FUNCTIONS.register(
            "kill_weight_four_rabbit_drops_three_extra_raw_rabbit_and_hide",
            () -> RabbitGameTests::killWeightFourRabbitDropsThreeExtraRawRabbitAndHide);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_RABBIT_DOES_NOT_AMPLIFY_RABBIT_FOOT = TEST_FUNCTIONS.register(
            "kill_weight_four_rabbit_does_not_amplify_rabbit_foot",
            () -> RabbitGameTests::killWeightFourRabbitDoesNotAmplifyRabbitFoot);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_EIGHT_RABBIT_DROPS_SEVEN_EXTRA = TEST_FUNCTIONS.register(
            "kill_weight_eight_rabbit_drops_seven_extra",
            () -> RabbitGameTests::killWeightEightRabbitDropsSevenExtra);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_RABBIT_HAS_SLOWNESS = TEST_FUNCTIONS.register(
            "weight_zero_rabbit_has_slowness",
            () -> RabbitGameTests::weightZeroRabbitHasSlowness);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_RABBIT_CANNOT_BREED = TEST_FUNCTIONS.register(
            "weight_zero_rabbit_cannot_breed",
            () -> RabbitGameTests::weightZeroRabbitCannotBreed);

    // ----------------------------------------------------------------------
    // Run-003: Mooshroom suite — design.md "Mooshroom" coverage targets
    // Verifies AbstractCow refactor: Mooshroom is a SIBLING of Cow in MC 26.1.
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MOOSHROOM_IN_PERFECT_PEN_WEIGHT_ONE_TO_TWO = TEST_FUNCTIONS.register(
            "mooshroom_in_perfect_pen_weight_one_to_two",
            () -> MooshroomGameTests::mooshroomInPerfectPenWeightOneToTwo);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_MOOSHROOM_DROPS_THREE_EXTRA_BEEF_AND_LEATHER = TEST_FUNCTIONS.register(
            "kill_weight_four_mooshroom_drops_three_extra_beef_and_leather",
            () -> MooshroomGameTests::killWeightFourMooshroomDropsThreeExtraBeefAndLeather);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_MOOSHROOM_HAS_SLOWNESS = TEST_FUNCTIONS.register(
            "weight_zero_mooshroom_has_slowness",
            () -> MooshroomGameTests::weightZeroMooshroomHasSlowness);

    // ----------------------------------------------------------------------
    // Run-004: BFS reachability suite — design.md sections A + B
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_IN_FENCED_2X2_PEN_LOSES_WEIGHT = TEST_FUNCTIONS.register(
            "cow_in_fenced_2x2_pen_loses_weight",
            () -> BfsReachabilityGameTests::cowInFencedTwoByTwoPenLosesWeight);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_ON_TOP_OF_FENCE_CANNOT_REACH_OUTSIDE = TEST_FUNCTIONS.register(
            "cow_on_top_of_fence_cannot_reach_outside",
            () -> BfsReachabilityGameTests::cowOnTopOfFenceCannotReachOutside);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_BLOCKED_BY_OTHER_ANIMAL_HAS_SMALLER_REACH = TEST_FUNCTIONS.register(
            "cow_blocked_by_other_animal_has_smaller_reach",
            () -> BfsReachabilityGameTests::cowBlockedByOtherAnimalHasSmallerReach);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_WITH_EXACTLY_SIX_REACHABLE_CELLS_PASSES_STRETCHING = TEST_FUNCTIONS.register(
            "cow_with_exactly_six_reachable_cells_passes_stretching",
            () -> BfsReachabilityGameTests::cowWithExactlySixReachableCellsPassesStretching);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            COW_WITH_FIVE_REACHABLE_CELLS_FAILS_STRETCHING = TEST_FUNCTIONS.register(
            "cow_with_five_reachable_cells_fails_stretching",
            () -> BfsReachabilityGameTests::cowWithFiveReachableCellsFailsStretching);

    // ----------------------------------------------------------------------
    // Run-004: Baby skip suite — design.md section D
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            BABY_COW_DOES_NOT_GET_EVALUATED = TEST_FUNCTIONS.register(
            "baby_cow_does_not_get_evaluated",
            () -> BabySkipGameTests::babyCowDoesNotGetEvaluated);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            BABY_COW_DOES_NOT_GET_SLOWNESS = TEST_FUNCTIONS.register(
            "baby_cow_does_not_get_slowness",
            () -> BabySkipGameTests::babyCowDoesNotGetSlowness);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ADULT_COW_IN_SAME_CONDITIONS_DOES_GET_SLOWNESS = TEST_FUNCTIONS.register(
            "adult_cow_in_same_conditions_does_get_slowness",
            () -> BabySkipGameTests::adultCowInSameConditionsDoesGetSlowness);

    // ----------------------------------------------------------------------
    // Run-004: Sick drop reduction — design.md section E (cap-to-1 only)
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SICK_COW_DROPS_CAPPED_TO_ONE_PER_PRIMARY = TEST_FUNCTIONS.register(
            "sick_cow_drops_capped_to_one_per_primary",
            () -> SickDropReductionGameTests::sickCowDropsCappedToOnePerPrimary);

    // ----------------------------------------------------------------------
    // Run-004: Water cauldron + reachable-only water — design.md sections F + G
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CAULDRON_INSIDE_PEN_COUNTS_AS_WATER = TEST_FUNCTIONS.register(
            "cauldron_inside_pen_counts_as_water",
            () -> WaterCauldronGameTests::cauldronInsidePenCountsAsWater);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            EMPTY_CAULDRON_DOES_NOT_COUNT = TEST_FUNCTIONS.register(
            "empty_cauldron_does_not_count",
            () -> WaterCauldronGameTests::emptyCauldronDoesNotCount);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SUNKEN_CAULDRON_Y_MINUS_ONE_COUNTS = TEST_FUNCTIONS.register(
            "sunken_cauldron_y_minus_one_counts",
            () -> WaterCauldronGameTests::sunkenCauldronYMinusOneCounts);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WATER_SOURCE_ACROSS_FENCE_DOES_NOT_COUNT = TEST_FUNCTIONS.register(
            "water_source_across_fence_does_not_count",
            () -> WaterCauldronGameTests::waterSourceAcrossFenceDoesNotCount);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WATER_SOURCE_INSIDE_PEN_COUNTS = TEST_FUNCTIONS.register(
            "water_source_inside_pen_counts",
            () -> WaterCauldronGameTests::waterSourceInsidePenCounts);

    // ----------------------------------------------------------------------
    // Run-004: Accelerating drop curve — design.md section H
    // ----------------------------------------------------------------------

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_SIX_COW_DROPS_SIX_EXTRA_BEEF = TEST_FUNCTIONS.register(
            "kill_weight_six_cow_drops_six_extra_beef",
            () -> DropCurveGameTests::killWeightSixCowDropsSixExtraBeef);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_SEVEN_COW_DROPS_EIGHT_EXTRA_BEEF = TEST_FUNCTIONS.register(
            "kill_weight_seven_cow_drops_eight_extra_beef",
            () -> DropCurveGameTests::killWeightSevenCowDropsEightExtraBeef);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_EIGHT_COW_DROPS_ELEVEN_EXTRA_BEEF = TEST_FUNCTIONS.register(
            "kill_weight_eight_cow_drops_eleven_extra_beef",
            () -> DropCurveGameTests::killWeightEightCowDropsElevenExtraBeef);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            XP_AT_WEIGHT_EIGHT_USES_SAME_CURVE = TEST_FUNCTIONS.register(
            "xp_at_weight_eight_uses_same_curve",
            () -> DropCurveGameTests::xpAtWeightEightUsesSameCurve);

    // ----------------------------------------------------------------------
    // Run-005: v0.2.0 parity additions
    // ----------------------------------------------------------------------

    // Display overlay (task-5) — DisplayOverlayHandler.
    // overlay_is_not_saved_to_disk is deferred to manual playtest: it requires
    // a chunk-reload boundary that a single-tick GameTest cell cannot reproduce.
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            TARGET_SPECIES_ANIMAL_GETS_OVERLAY_TEXT_DISPLAY = TEST_FUNCTIONS.register(
            "target_species_animal_gets_overlay_text_display",
            () -> DisplayOverlayGameTests::targetSpeciesAnimalGetsOverlayTextDisplay);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            OVERLAY_DESPAWNS_ON_ANIMAL_DEATH = TEST_FUNCTIONS.register(
            "overlay_despawns_on_animal_death",
            () -> DisplayOverlayGameTests::overlayDespawnsOnAnimalDeath);

    // Wander-to-habitat goal (task-6) — WanderToHabitatGoal + HabitatGoalInstaller.
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ADULT_COW_HAS_HABITAT_GOAL_INSTALLED_ON_SPAWN = TEST_FUNCTIONS.register(
            "adult_cow_has_habitat_goal_installed_on_spawn",
            () -> WanderHabitatGoalGameTests::adultCowHasHabitatGoalInstalledOnSpawn);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            BABY_COW_DOES_NOT_HAVE_HABITAT_GOAL_INSTALLED = TEST_FUNCTIONS.register(
            "baby_cow_does_not_have_habitat_goal_installed",
            () -> WanderHabitatGoalGameTests::babyCowDoesNotHaveHabitatGoalInstalled);

    // Weakness mob effect (task-7) — second sick effect alongside Slowness.
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ZERO_TARGET_SPECIES_GETS_BOTH_SLOWNESS_AND_WEAKNESS = TEST_FUNCTIONS.register(
            "weight_zero_target_species_gets_both_slowness_and_weakness",
            () -> WeaknessEffectGameTests::weightZeroTargetSpeciesGetsBothSlownessAndWeakness);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WEIGHT_ONE_TARGET_SPECIES_GETS_NEITHER_SLOWNESS_NOR_WEAKNESS = TEST_FUNCTIONS.register(
            "weight_one_target_species_gets_neither_slowness_nor_weakness",
            () -> WeaknessEffectGameTests::weightOneTargetSpeciesGetsNeitherSlownessNorWeakness);

    // Village/water passive bonus (task-8) — DropScalingHandler.isNearVillageOrWater.
    // Village-proximity coverage is deferred to manual playtest (POI setup is
    // impractical in a GameTest cell). Water side of the OR clause is covered here.
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ONE_COW_NEAR_WATER_DROPS_ONE_EXTRA_BEEF = TEST_FUNCTIONS.register(
            "kill_weight_one_cow_near_water_drops_one_extra_beef",
            () -> VillageWaterBonusGameTests::killWeightOneCowNearWaterDropsOneExtraBeef);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ONE_COW_AWAY_FROM_WATER_DROPS_VANILLA = TEST_FUNCTIONS.register(
            "kill_weight_one_cow_away_from_water_drops_vanilla",
            () -> VillageWaterBonusGameTests::killWeightOneCowAwayFromWaterDropsVanilla);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_ZERO_COW_NEAR_WATER_GETS_NO_VILLAGE_BONUS = TEST_FUNCTIONS.register(
            "kill_weight_zero_cow_near_water_gets_no_village_bonus",
            () -> VillageWaterBonusGameTests::killWeightZeroCowNearWaterGetsNoVillageBonus);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            KILL_WEIGHT_FOUR_COW_NEAR_WATER_DROPS_MULTIPLICATIVE_PLUS_ONE = TEST_FUNCTIONS.register(
            "kill_weight_four_cow_near_water_drops_multiplicative_plus_one",
            () -> VillageWaterBonusGameTests::killWeightFourCowNearWaterDropsMultiplicativePlusOne);
}
