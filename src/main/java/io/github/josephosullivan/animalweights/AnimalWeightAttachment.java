package io.github.josephosullivan.animalweights;

import com.mojang.serialization.Codec;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Per-entity integer weight attachment.
 *
 * <p>Backed by NeoForge's {@link AttachmentType}, so the value is persisted in
 * the entity's NBT automatically. The attachment is defined on the generic
 * {@code IAttachmentHolder} surface (no entity-type filter); only the
 * Cow/Pig/Sheep consumers actually read or write it.
 *
 * <p>Helpers {@link #get(LivingEntity)} and {@link #set(LivingEntity, int)}
 * clamp to {@code [WEIGHT_MIN, WEIGHT_MAX]} and isolate callers from the
 * underlying {@code IAttachmentHolder} API.
 */
public final class AnimalWeightAttachment {

    private AnimalWeightAttachment() {
        // utility class — no instances
    }

    /** DeferredRegister handle; the orchestrator wires this into the mod event bus in {@link AnimalWeights}. */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, AnimalWeights.MOD_ID);

    /**
     * The registered weight attachment. Defaults to {@link AnimalWeightsTuning#WEIGHT_DEFAULT}.
     *
     * <p>Serialized via {@code Codec.INT.fieldOf("value")}: in NeoForge 26.1 the
     * {@link AttachmentType.Builder#serialize} entry points accept {@code MapCodec}
     * (or {@code IAttachmentSerializer}), not a bare {@link Codec}, so we wrap the
     * primitive int codec in a one-field map. The persisted NBT is a compound
     * containing a single {@code value:int} entry.
     */
    public static final Supplier<AttachmentType<Integer>> WEIGHT = ATTACHMENT_TYPES.register(
            "weight",
            () -> AttachmentType.builder(() -> AnimalWeightsTuning.WEIGHT_DEFAULT)
                    .serialize(Codec.INT.fieldOf("value"))
                    .build()
    );

    /**
     * Registers the attachment {@link DeferredRegister} onto the mod event bus.
     * Called once from {@link AnimalWeights#AnimalWeights(IEventBus)}.
     */
    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    /**
     * Returns the entity's current weight, or {@link AnimalWeightsTuning#WEIGHT_DEFAULT}
     * if no value has been set. Calling this on a fresh entity installs the default in
     * the underlying attachment map (the NeoForge {@code getData} contract).
     */
    public static int get(LivingEntity entity) {
        return entity.getData(WEIGHT);
    }

    /**
     * Single source of truth for "does this entity participate in Animal
     * Weights gameplay?" — consults the {@link AnimalWeightsTags#TRACKED}
     * entity-type tag. The default tag JSON ships with the six vanilla
     * target species (cow, mooshroom, pig, sheep, chicken, rabbit); modded
     * animals join by datapack contribution to the same tag.
     *
     * <p>Replaces the per-handler {@code instanceof AbstractCow || Pig ||
     * Sheep || Chicken || Rabbit} chains that previously hard-coded modded
     * mobs out of weight, sick state, drop scaling, habitat AI, the
     * overlay, and the sick tint.
     */
    public static boolean isTracked(Entity entity) {
        return entity.getType().builtInRegistryHolder().is(AnimalWeightsTags.TRACKED);
    }

    /**
     * Sets the entity's weight, clamped to {@code [WEIGHT_MIN, WEIGHT_MAX]}.
     *
     * @return the value actually stored after clamping
     */
    public static int set(LivingEntity entity, int weight) {
        int clamped = clamp(weight);
        entity.setData(WEIGHT, clamped);
        return clamped;
    }

    /**
     * Clamps {@code weight} into the configured {@code [WEIGHT_MIN, WEIGHT_MAX]} range.
     * Exposed for Tier-1 testability and for callers that want to validate input
     * before storing.
     */
    public static int clamp(int weight) {
        return Mth.clamp(weight, AnimalWeightsTuning.WEIGHT_MIN, AnimalWeightsTuning.WEIGHT_MAX);
    }
}
