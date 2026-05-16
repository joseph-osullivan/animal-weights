package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeightsTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * Bodies for the {@link AnimalWeightAttachment} Tier-2 GameTests.
 *
 * <p>The clamp helper is Tier-1 covered by {@code AnimalWeightAttachmentTest};
 * the four cases here pin the {@code get} / {@code set} entry points against
 * a real registry-bound {@link Cow}, plus the NBT roundtrip (deferred from
 * run 001's task-1).
 *
 * <p>Spec source: run-002 {@code design.md} "Weight attachment" section —
 * default == 1, set is clamped to {@code [0, 8]}, NBT roundtrip retains the
 * value across a fresh entity load.
 */
public final class WeightAttachmentGameTests {

    private WeightAttachmentGameTests() {}

    /**
     * Fresh, never-set cow → {@link AnimalWeightAttachment#get(net.minecraft.world.entity.LivingEntity)}
     * returns {@link AnimalWeightsTuning#WEIGHT_DEFAULT} (1). Pins the
     * "default 1 for unmanaged animals" contract from design.md.
     */
    public static void weightDefaultForFreshCow(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        int weight = AnimalWeightAttachment.get(cow);
        if (weight != AnimalWeightsTuning.WEIGHT_DEFAULT) {
            helper.fail("fresh cow weight expected " + AnimalWeightsTuning.WEIGHT_DEFAULT
                    + " (WEIGHT_DEFAULT); got " + weight);
            return;
        }
        helper.succeed();
    }

    /**
     * {@code set(cow, -3)} → stored value is 0 (clamped to
     * {@link AnimalWeightsTuning#WEIGHT_MIN}). The {@code clamp} helper has
     * Tier-1 coverage; this test pins the {@code set} entry point's contract
     * end-to-end against the actual attachment storage.
     */
    public static void weightSetClampsNegativeToZero(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        int returned = AnimalWeightAttachment.set(cow, -3);
        if (returned != AnimalWeightsTuning.WEIGHT_MIN) {
            helper.fail("set(cow, -3) returned " + returned
                    + "; expected WEIGHT_MIN (" + AnimalWeightsTuning.WEIGHT_MIN + ")");
            return;
        }
        int readBack = AnimalWeightAttachment.get(cow);
        if (readBack != AnimalWeightsTuning.WEIGHT_MIN) {
            helper.fail("get(cow) after set(cow, -3) returned " + readBack
                    + "; expected WEIGHT_MIN — clamp not actually persisted to attachment");
            return;
        }
        helper.succeed();
    }

    /**
     * {@code set(cow, 99)} → stored value is 8
     * ({@link AnimalWeightsTuning#WEIGHT_MAX}). Mirrors the negative test
     * for the upper bound.
     */
    public static void weightSetClampsOverflowToMax(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        int returned = AnimalWeightAttachment.set(cow, 99);
        if (returned != AnimalWeightsTuning.WEIGHT_MAX) {
            helper.fail("set(cow, 99) returned " + returned
                    + "; expected WEIGHT_MAX (" + AnimalWeightsTuning.WEIGHT_MAX + ")");
            return;
        }
        int readBack = AnimalWeightAttachment.get(cow);
        if (readBack != AnimalWeightsTuning.WEIGHT_MAX) {
            helper.fail("get(cow) after set(cow, 99) returned " + readBack
                    + "; expected WEIGHT_MAX — clamp not actually persisted to attachment");
            return;
        }
        helper.succeed();
    }

    /**
     * NBT save/load roundtrip: {@code set(cow, 5)} → serialise → load into a
     * fresh cow → {@code get()} returns 5. This is the test deferred from
     * run 001's task-1 ("verify attachment persists through entity save/load").
     *
     * <p>The attachment is serialised by NeoForge via the
     * {@code Codec.INT.fieldOf("value")} map codec registered in
     * {@link AnimalWeightAttachment}; that codec writes/reads inside the entity's
     * vanilla {@code saveWithoutId} flow. We exercise the same flow by
     * round-tripping through {@code TagValueOutput} / {@code TagValueInput}
     * (the modern NBT bridge used by NeoForge 26.1 entities).
     */
    public static void weightPersistsThroughNbtSaveLoad(GameTestHelper helper) {
        Cow original = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 2));
        AnimalWeightAttachment.set(original, 5);

        // Serialise the entity (including attachments) into NBT.
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess());
        original.saveWithoutId(out);
        CompoundTag tag = out.buildResult();

        // Load into a fresh cow on a different cell-relative position.
        Cow loaded = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 4));
        ValueInput in = TagValueInput.create(
                ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(),
                tag);
        loaded.load(in);

        int weight = AnimalWeightAttachment.get(loaded);
        if (weight != 5) {
            helper.fail("weight after NBT roundtrip expected 5; got " + weight
                    + " — attachment serialisation is broken");
            return;
        }
        helper.succeed();
    }
}
