package io.github.josephosullivan.animalweights.gametest;

import io.github.josephosullivan.animalweights.event.DisplayOverlayHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Bodies for Tier-2 GameTests covering the floating-text
 * {@link DisplayOverlayHandler} added in run-005 task-5.
 *
 * <p>Spec source: {@code docs/workflow-runs/005-v0.2.0-parity/decisions.md} Q1
 * — "Text-only display overlay — floating text above each animal (weight +
 * bonus). NO sampled-loot-icon ItemDisplays; no Codec refactor of the int-only
 * AnimalWeightAttachment." Same target-species predicate as
 * {@code SickStateHandler}: Cow/Mooshroom (any {@code AbstractCow}), Pig,
 * Sheep, Chicken, Rabbit.
 *
 * <p><b>Throttle awareness:</b> the production handler is throttled to ~every
 * 16 ticks per entity ({@code (gameTime + id) & 15}), so we wait 40 ticks
 * before checking — guarantees at least two throttled ticks have fired
 * regardless of the entity's id offset, with double-period margin for slow
 * CI runners (GitHub Actions has been observed running ~46 ticks behind
 * during test boot).
 *
 * <p><b>Persistence not GameTestable here:</b> the
 * {@code overlay_is_not_saved_to_disk} contract (per
 * {@link DisplayOverlayHandler#OVERLAY_TAG} sweep on
 * {@code EntityJoinLevelEvent.loadedFromDisk()}) requires a chunk-reload
 * boundary, which a single-tick GameTest cell cannot reproduce. That branch
 * is exercised by manual playtest only — deferred per the same playtest list
 * as the {@code /animalweights} command and the sick render tint.
 */
public final class DisplayOverlayGameTests {

    private DisplayOverlayGameTests() {}

    private static final BlockPos COW_REL = new BlockPos(2, 2, 2);

    /**
     * The throttle period from {@link DisplayOverlayHandler} is 16 ticks
     * ({@code THROTTLE_MASK = 15}), entity-id-offset. Worst-case latency
     * before the first refresh is 15 ticks. Wait 80 to give 5× throttle-period
     * margin for the addFreshEntity → first-tick → throttle-hit pipeline on
     * slow CI runners — GitHub Actions has been observed running ~46 ticks
     * behind during test boot, and the previous 40-tick margin still flaked
     * under that load. If 80 ticks still isn't enough, the failure is not a
     * margin issue but a real handler bug.
     */
    private static final long OVERLAY_WAIT_TICKS = 80L;

    /**
     * Spec row (decisions.md Q1): "Text-only display overlay — floating text
     * above each animal." After spawning a target-species adult and letting
     * the handler throttle fire, a {@link Display.TextDisplay} entity tagged
     * with {@link DisplayOverlayHandler#OVERLAY_TAG} must exist within a
     * small AABB centred on the cow.
     *
     * <p>Bug it catches: the handler doesn't fire, doesn't tag the spawn, or
     * spawns the display at a wildly-wrong position. The OVERLAY_TAG filter
     * is load-bearing — without it, any pre-existing TextDisplay in the test
     * world (vanilla command blocks, etc.) could falsely satisfy the
     * assertion.
     */
    public static void targetSpeciesAnimalGetsOverlayTextDisplay(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        cow.setNoGravity(true); // empty template has no floor — keep position stable
        UUID cowId = cow.getUUID();

        helper.runAfterDelay(OVERLAY_WAIT_TICKS, () -> {
            // Search a 3-block-radius AABB around the cow's absolute position.
            // The overlay is placed at cow.y + bbHeight + 0.6, so it sits a
            // shade above the cow's head — well within 3 blocks vertically.
            BlockPos abs = helper.absolutePos(COW_REL);
            AABB searchBox = AABB.ofSize(Vec3.atCenterOf(abs), 6.0, 6.0, 6.0);
            List<Display.TextDisplay> displays = helper.getLevel().getEntitiesOfClass(
                    Display.TextDisplay.class,
                    searchBox,
                    d -> d.entityTags().contains(DisplayOverlayHandler.OVERLAY_TAG));

            if (displays.isEmpty()) {
                // Verify the cow itself is still where we expect — if the cow
                // died or moved out of cell, that's a setup failure, not a
                // handler failure.
                if (!cow.isAlive()) {
                    helper.fail("test setup: cow died during the wait window");
                    return;
                }
                if (!cow.getUUID().equals(cowId)) {
                    helper.fail("test setup: cow entity reference changed during wait");
                    return;
                }
                helper.fail("no OVERLAY_TAG Display.TextDisplay found within 3 blocks of cow at " + abs
                        + " after " + OVERLAY_WAIT_TICKS + " ticks — DisplayOverlayHandler did not spawn "
                        + "a follower display on the throttled refresh tick");
                return;
            }
            if (displays.size() > 1) {
                helper.fail("expected exactly one overlay display; got " + displays.size()
                        + " — handler may be spawning duplicates per refresh");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Spec row (decisions.md Q1 + class javadoc on
     * {@link DisplayOverlayHandler}): "Discard on death — {@code LivingDeathEvent}
     * — look up the follower display by UUID and {@code discard()} it."
     *
     * <p>Sequence: spawn cow, wait for overlay to attach, kill cow, wait a
     * tick for the discard to flush, assert no tagged overlay remains in the
     * search AABB.
     *
     * <p>Bug it catches: the handler's death listener doesn't fire, or fires
     * but doesn't find the follower (UUID mismatch), or fires but the
     * {@code discard()} call no-ops. Without this test, overlays would leak
     * — every dead cow leaves a "Weight N" floating in the air.
     */
    public static void overlayDespawnsOnAnimalDeath(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, COW_REL);
        cow.setNoGravity(true);

        helper.runAfterDelay(OVERLAY_WAIT_TICKS, () -> {
            // Step 1: verify overlay exists (precondition for the death test).
            BlockPos abs = helper.absolutePos(COW_REL);
            AABB searchBox = AABB.ofSize(Vec3.atCenterOf(abs), 6.0, 6.0, 6.0);
            List<Display.TextDisplay> beforeDeath = helper.getLevel().getEntitiesOfClass(
                    Display.TextDisplay.class,
                    searchBox,
                    d -> d.entityTags().contains(DisplayOverlayHandler.OVERLAY_TAG));
            if (beforeDeath.isEmpty()) {
                helper.fail("test precondition failed: no overlay attached to cow before death — "
                        + "cannot verify discard-on-death without an overlay to discard");
                return;
            }

            // Step 2: kill the cow with a bypass-armor source.
            cow.hurtServer(helper.getLevel(),
                    helper.getLevel().damageSources().genericKill(),
                    1000.0f);
            if (cow.isAlive()) {
                helper.fail("test setup: cow survived 1000-damage genericKill — "
                        + "damage source not lethal in this MC version");
                return;
            }

            // Step 3: wait a tick or two so LivingDeathEvent → discardFollower
            // has flushed. The handler discards synchronously on death (not
            // queued through PENDING_DISCARDS — that's the chunk-unload path).
            helper.runAfterDelay(2L, () -> {
                List<Display.TextDisplay> afterDeath = helper.getLevel().getEntitiesOfClass(
                        Display.TextDisplay.class,
                        searchBox,
                        d -> d.entityTags().contains(DisplayOverlayHandler.OVERLAY_TAG));
                if (!afterDeath.isEmpty()) {
                    helper.fail("found " + afterDeath.size() + " OVERLAY_TAG display(s) within 3 blocks of "
                            + abs + " 2 ticks after cow death — DisplayOverlayHandler.onLivingDeath did not "
                            + "discard the follower (UUID mismatch, or listener not firing)");
                    return;
                }
                helper.succeed();
            });
        });
    }
}
