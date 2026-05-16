package io.github.josephosullivan.animalweights.event;

import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Floating-text {@link Display.TextDisplay} overlay above each target-species
 * animal, showing its current {@link AnimalWeightAttachment weight} (or
 * "Sick" when the weight is 0).
 *
 * <p>Locked decisions (run-005 {@code decisions.md} Q1): text-only, NO sampled
 * loot-icon {@code ItemDisplay}s; no Codec refactor of the int-only
 * {@code AnimalWeightAttachment}. Membership is the
 * {@link io.github.josephosullivan.animalweights.AnimalWeightsTags#TRACKED}
 * tag — by default cow, mooshroom, pig, sheep, chicken, rabbit; extensible
 * to modded animals via datapack.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li><b>Spawn</b>: on {@link EntityTickEvent.Post}, throttled to ~every
 *       16th tick per animal (entity-id scattered — {@code (gameTime + id) & 15})
 *       so we never iterate every-target-species-every-tick. On the throttled
 *       tick: if the animal has no live follower display, spawn a
 *       {@link Display.TextDisplay} above it via
 *       {@link EntityType#create(net.minecraft.world.level.Level, EntitySpawnReason)}
 *       and load its text NBT via {@link Entity#load(ValueInput)}; otherwise
 *       refresh text + position only if the weight changed since the last
 *       update.</li>
 *   <li><b>Discard on death</b>: {@link LivingDeathEvent} — look up the
 *       follower display by UUID and {@code discard()} it.</li>
 *   <li><b>Discard on chunk unload</b>: {@link EntityLeaveLevelEvent} — same
 *       treatment for animals that leave the level (chunk unload, dimension
 *       change, despawn) so we don't leak orphaned displays.</li>
 *   <li><b>Discard on chunk reload</b>: {@link EntityJoinLevelEvent} — Display
 *       entities are saved to disk by vanilla (their EntityType is
 *       {@link EntityType#TEXT_DISPLAY} with default serialize=true; there is
 *       no public Entity#setPersistenceRequired(false) for non-Mob entities
 *       and Display does not override shouldBeSaved). We mark every overlay
 *       we spawn with a unique scoreboard tag ({@link #OVERLAY_TAG}) so any
 *       tagged display that re-enters the level on chunk reload (i.e.
 *       {@link EntityJoinLevelEvent#loadedFromDisk()} is true) gets discarded
 *       on sight, effectively making the overlay non-persistent across
 *       save/reload boundaries.</li>
 * </ul>
 *
 * <h2>Tracking</h2>
 * <p>Per-{@link ServerLevel} maps:
 * <ul>
 *   <li>{@code animalToDisplay} — animal UUID → display UUID. We look up the
 *       follower each refresh via {@link ServerLevel#getEntity(UUID)} so a
 *       cross-tick reference can never go stale.</li>
 *   <li>{@code lastWeight} — animal UUID → last-applied weight. Avoids
 *       re-issuing identical {@code load()} calls (the {@code text} JSON
 *       contains the weight number so an unchanged weight means an unchanged
 *       Component).</li>
 * </ul>
 *
 * <p>{@link WeakHashMap} keyed by the {@link ServerLevel} so an unloaded
 * dimension's entries GC automatically; no leak.
 *
 * <p>Server-side only — Display.TextDisplay is server-spawned and clients see
 * it via normal entity-tracker sync. No client class touched.
 */
@EventBusSubscriber(modid = AnimalWeights.MOD_ID)
public final class DisplayOverlayHandler {

    /**
     * Scoreboard tag attached to every overlay display we spawn. Used to
     * detect (and discard) saved overlays that the chunk system restores on
     * reload — see class javadoc for why we cannot mark them
     * non-persistent at the EntityType layer.
     */
    public static final String OVERLAY_TAG = "animalweights_overlay";

    /**
     * Throttle mask: refresh checks happen on game ticks where
     * {@code (gameTime + entity.getId()) & THROTTLE_MASK == 0}. With mask
     * {@code 15} that's ~once every 16 ticks per animal, scattered across
     * the tick window to avoid herd refreshes. 16 ticks = 0.8 s — plenty
     * responsive for a passive weight display while keeping per-tick load
     * negligible.
     */
    private static final int THROTTLE_MASK = 15;

    /**
     * Vertical offset (blocks) above the animal's eye height where the
     * overlay is placed. 0.6 leaves a small gap above the head for
     * readability without occluding the animal's hitbox in third-person.
     */
    private static final double VERTICAL_OFFSET_BLOCKS = 0.6;

    /**
     * Pos-rot interpolation duration (in ticks) baked into the display NBT.
     * Matches {@link #THROTTLE_MASK}+1: when we update the display's
     * position on the next throttled tick, the client interpolates over
     * 16 ticks for a smooth follow.
     */
    private static final int TELEPORT_INTERPOLATION_DURATION_TICKS = 16;

    /**
     * Tracks the follower display UUID per animal UUID, per level. WeakHashMap
     * keyed by the level so unloaded dimensions' entries GC.
     */
    private static final Map<ServerLevel, Map<UUID, UUID>> ANIMAL_TO_DISPLAY = new WeakHashMap<>();

    /**
     * Tracks the last weight pushed into each animal's display. Avoids
     * re-issuing identical NBT loads on refresh ticks where nothing has
     * changed.
     */
    private static final Map<ServerLevel, Map<UUID, Integer>> LAST_WEIGHT = new WeakHashMap<>();

    /**
     * Pending discard queue, per level. Drained on {@link ServerTickEvent.Post}.
     *
     * <p>{@link EntityLeaveLevelEvent} fires from inside
     * {@code PersistentEntitySectionManager.updateChunkStatus} while MC is
     * iterating its entity list — calling {@link Entity#discard()}
     * synchronously there mutates the list mid-iteration and throws
     * {@code ConcurrentModificationException}. We can't use
     * {@code MinecraftServer#execute(Runnable)} either: when called from the
     * server thread outside a {@code doRunTask} cycle, it runs the runnable
     * inline (see {@code BlockableEventLoop.execute} / {@code scheduleExecutables}).
     * The {@code tell(TickTask)} path is also unavailable because
     * {@code ProcessorHandle} isn't exposed on the public compile classpath.
     *
     * <p>Solution: append the {@code (level, animalId)} pair to this queue
     * from the event, then drain it on the next {@code ServerTickEvent.Post}
     * — guaranteed to run after {@code updateChunkStatus} has returned.
     */
    private static final Map<ServerLevel, Deque<UUID>> PENDING_DISCARDS = new WeakHashMap<>();

    private DisplayOverlayHandler() {
        // event-handler entry points are static; no instances
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        // Throttle: ~every 16 ticks per animal, scattered across the window
        // by entity id. Same shape as SickStateHandler's 8-tick throttle.
        if (((level.getGameTime() + entity.getId()) & THROTTLE_MASK) != 0) {
            return;
        }
        if (!AnimalWeightAttachment.isTracked(entity)) {
            return;
        }
        if (!(entity instanceof LivingEntity animal)) {
            return; // tag could legally contain non-LivingEntity types; gate before cast
        }
        if (animal instanceof Mob mob && mob.isBaby()) {
            // Babies don't participate in the weight mechanic, so they don't
            // need an overlay either. Matches SickStateHandler's gating.
            return;
        }

        Map<UUID, UUID> animalToDisplay = ANIMAL_TO_DISPLAY
                .computeIfAbsent(level, k -> new java.util.HashMap<>());
        Map<UUID, Integer> lastWeight = LAST_WEIGHT
                .computeIfAbsent(level, k -> new java.util.HashMap<>());

        int weight = AnimalWeightAttachment.get(animal);
        UUID animalId = animal.getUUID();
        UUID displayId = animalToDisplay.get(animalId);
        Display.TextDisplay display = displayId == null
                ? null
                : (level.getEntity(displayId) instanceof Display.TextDisplay td ? td : null);

        if (display == null) {
            // No follower (first observation, or the previous one despawned
            // — e.g. chunk reload sweep) — spawn one.
            Display.TextDisplay spawned = spawnOverlay(level, animal, weight);
            if (spawned != null) {
                animalToDisplay.put(animalId, spawned.getUUID());
                lastWeight.put(animalId, weight);
            }
            return;
        }

        // Follower exists. Reposition every refresh so the display tracks
        // the animal as it walks; the baked-in teleport_duration smooths
        // the discrete jumps client-side.
        display.snapTo(
                animal.getX(),
                animal.getY() + animal.getBbHeight() + VERTICAL_OFFSET_BLOCKS,
                animal.getZ());

        // Only re-push text NBT when the weight actually changed.
        Integer last = lastWeight.get(animalId);
        if (last == null || last != weight) {
            applyText(display, weight);
            lastWeight.put(animalId, weight);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (!AnimalWeightAttachment.isTracked(entity)) {
            return;
        }
        discardFollower(level, entity.getUUID());
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (!AnimalWeightAttachment.isTracked(entity)) {
            return;
        }
        // EntityLeaveLevelEvent fires from inside
        // PersistentEntitySectionManager.updateChunkStatus while MC is
        // iterating its entity list — calling Entity#discard() synchronously
        // here mutates that list mid-iteration and throws
        // ConcurrentModificationException. Defer to the next ServerTickEvent.Post.
        // See PENDING_DISCARDS javadoc for why neither MinecraftServer#execute
        // nor tell(TickTask) work here.
        PENDING_DISCARDS
                .computeIfAbsent(level, k -> new ArrayDeque<>())
                .add(entity.getUUID());
    }

    /**
     * Discard-on-reload sweep: any tagged overlay that re-enters a level via
     * disk load (i.e. a chunk-stored Display from a prior session) gets
     * cancelled at the join point. This is the "non-persistent" guarantee —
     * we tag every overlay we spawn and never let a saved one stay alive.
     *
     * <p>Cancelling the event prevents the entity from joining the level at
     * all (NeoForge 26.1: {@link EntityJoinLevelEvent} is cancellable).
     * Discarding synchronously would mutate the chunk-status entity list
     * mid-iteration → {@code ConcurrentModificationException}. See run-005
     * hotfix.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Display.TextDisplay)) {
            return;
        }
        if (!entity.entityTags().contains(OVERLAY_TAG)) {
            return;
        }
        event.setCanceled(true);
    }

    /**
     * Drain the {@link #PENDING_DISCARDS} queue once per server tick. By the
     * time this fires, all {@code updateChunkStatus} iterations from this
     * tick have completed, so {@code Entity#discard()} is safe to call.
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Deque<UUID> queue = PENDING_DISCARDS.get(level);
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            UUID animalId;
            while ((animalId = queue.poll()) != null) {
                discardFollower(level, animalId);
            }
        }
    }

    /**
     * Look up the follower display for an animal and discard it. Idempotent
     * — safe to call on animals that never had a follower.
     */
    private static void discardFollower(ServerLevel level, UUID animalId) {
        Map<UUID, UUID> animalToDisplay = ANIMAL_TO_DISPLAY.get(level);
        if (animalToDisplay == null) {
            return;
        }
        UUID displayId = animalToDisplay.remove(animalId);
        if (displayId == null) {
            return;
        }
        Map<UUID, Integer> lastWeight = LAST_WEIGHT.get(level);
        if (lastWeight != null) {
            lastWeight.remove(animalId);
        }
        Entity follower = level.getEntity(displayId);
        if (follower != null) {
            follower.discard();
        }
    }

    /**
     * Build a fresh {@link Display.TextDisplay} above {@code animal}, tag it,
     * apply the weight-text NBT, and add it to the level. Returns the spawned
     * entity, or {@code null} if creation failed (EntityType disabled by
     * feature flags, etc. — should never happen for vanilla TEXT_DISPLAY).
     */
    private static Display.TextDisplay spawnOverlay(ServerLevel level, LivingEntity animal, int weight) {
        // EntityType.create(Level, EntitySpawnReason) is the public path in
        // MC 26.1; the 1-arg create(Level) overload was removed. LOAD is the
        // canonical reason for non-natural mod-spawned utility entities.
        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.LOAD);
        if (display == null) {
            return null;
        }
        double x = animal.getX();
        double y = animal.getY() + animal.getBbHeight() + VERTICAL_OFFSET_BLOCKS;
        double z = animal.getZ();
        display.snapTo(x, y, z);
        display.addTag(OVERLAY_TAG);
        applyText(display, weight);
        if (!level.addFreshEntity(display)) {
            // addFreshEntity returned false (e.g. entity already loaded with
            // same id, or chunk wasn't ready). Discard so we don't leak a
            // half-initialized entity reference.
            display.discard();
            return null;
        }
        return display;
    }

    /**
     * Push the weight-text Component into {@code display} via the public
     * {@link Entity#load(ValueInput)} path. {@code Display.TextDisplay#setText}
     * is private in vanilla, so we cannot call it directly. The supported
     * public path is the NBT load: {@code readAdditionalSaveData(ValueInput)}
     * reads the {@code "text"} field via {@link ComponentSerialization#CODEC}
     * and pushes it into the synched DATA_TEXT_ID for client visibility.
     *
     * <p>In MC 26.1, entity load/save migrated from {@code CompoundTag} to the
     * codec-backed {@link ValueInput}/{@code ValueOutput} pair. We build a
     * minimal {@link CompoundTag} (Pos / Motion / Rotation / Tags / text /
     * billboard / teleport_duration), wrap it via
     * {@link TagValueInput#create}, and pass that to {@code load()}.
     */
    private static void applyText(Display.TextDisplay display, int weight) {
        Component text = buildText(weight);

        CompoundTag tag = new CompoundTag();

        // Position/motion/rotation must be present — Entity#load reads them
        // unconditionally and would otherwise zero them out. Preserve the
        // values we already set with snapTo before this call.
        tag.put("Pos", listOfDoubles(display.getX(), display.getY(), display.getZ()));
        tag.put("Motion", listOfDoubles(0.0, 0.0, 0.0));
        tag.put("Rotation", listOfFloats(display.getYRot(), display.getXRot()));

        // Preserve our overlay tag across the load so the persistence-sweep
        // in onEntityJoinLevel still recognises post-reload entities.
        ListTag tags = new ListTag();
        for (String t : display.entityTags()) {
            tags.add(StringTag.valueOf(t));
        }
        tag.put("Tags", tags);

        // Text via the canonical Component codec — encodes the same NBT
        // structure that vanilla writes in addAdditionalSaveData, so the
        // readAdditionalSaveData round-trip is symmetric.
        Tag encodedText = ComponentSerialization.CODEC
                .encodeStart(NbtOps.INSTANCE, text)
                .getOrThrow(msg -> new IllegalStateException("Failed to encode display text: " + msg));
        tag.put("text", encodedText);

        // billboard=center so the text always faces the player; teleport_duration
        // matches our refresh cadence so position updates interpolate smoothly.
        tag.putString("billboard", "center");
        tag.putInt("teleport_duration", TELEPORT_INTERPOLATION_DURATION_TICKS);

        // MC 26.1 migrated Entity#load(CompoundTag) → Entity#load(ValueInput).
        // TagValueInput wraps a CompoundTag in a ValueInput with the entity's
        // registry context so codecs that need registry lookups (e.g. text
        // tags resolving styles) succeed. DISCARDING swallows minor decoding
        // problems instead of throwing — the only field that could realistically
        // fail is the text codec, and we just encoded it ourselves.
        ValueInput valueInput = TagValueInput.create(
                ProblemReporter.DISCARDING, display.registryAccess(), tag);
        display.load(valueInput);
    }

    /**
     * Compose the visible Component. Weight 0 reads as "Sick" to match the
     * SickStateHandler semantics; otherwise "Weight N".
     */
    private static Component buildText(int weight) {
        if (weight == 0) {
            return Component.literal("Sick");
        }
        return Component.literal("Weight " + weight);
    }

    private static ListTag listOfDoubles(double a, double b, double c) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(a));
        list.add(DoubleTag.valueOf(b));
        list.add(DoubleTag.valueOf(c));
        return list;
    }

    private static ListTag listOfFloats(float a, float b) {
        ListTag list = new ListTag();
        list.add(FloatTag.valueOf(a));
        list.add(FloatTag.valueOf(b));
        return list;
    }
}
