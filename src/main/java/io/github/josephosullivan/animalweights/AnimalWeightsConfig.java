package io.github.josephosullivan.animalweights;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Runtime configuration for the Animal Weights mod, split into a server spec
 * (gameplay tuning) and a client spec (visual tuning).
 *
 * <p>Server tuning lives in {@code config/animalweights-server.toml} and is
 * world-specific overridable; client tuning lives in
 * {@code config/animalweights-client.toml}.
 *
 * <p><b>What's not here, by design:</b> {@code WEIGHT_MIN},
 * {@code WEIGHT_MAX}, {@code WEIGHT_DEFAULT}. Those constants live in
 * {@link AnimalWeightsTuning} because they are save-format-coupled — the
 * attachment storage and clamping logic assume a fixed range, and changing
 * the range at runtime would corrupt existing entity attachments. They are
 * compile-time constants and stay that way.
 *
 * <p>API shape: {@link ModConfigSpec.Builder#configure(java.util.function.Function)}
 * returns a {@code Pair<T, ModConfigSpec>}; we keep both the typed holder
 * (so call sites can do {@code SERVER.evalRadius.get()}) and the raw spec
 * (so the mod constructor can register it with the {@code ModContainer}).
 */
public final class AnimalWeightsConfig {

    private AnimalWeightsConfig() {
        // holder of constants — no instances
    }

    /**
     * Server-side gameplay tuning. Synced to clients via NeoForge's
     * {@code ModConfig.Type.SERVER} machinery so the world-specific override
     * file (if any) wins over the per-server default.
     */
    public static final class Server {
        /** Half-extent (in blocks) of the cube searched around a mob during dawn eval. */
        public final ModConfigSpec.IntValue evalRadius;
        /** Block-light level (0-15) considered bright enough to satisfy the "light" condition. */
        public final ModConfigSpec.IntValue lightThreshold;
        /** Minimum reachable cells for the "stretching room" condition to pass. */
        public final ModConfigSpec.IntValue minRoamingCells;
        /** Hard cap on cells the reachability BFS will enumerate per mob. */
        public final ModConfigSpec.IntValue roamingMaxBfsCells;
        /** Probability that a sick mob's primary drop is removed entirely (vs clamped to 1). */
        public final ModConfigSpec.DoubleValue sickDropRemovalChance;
        /** Half-extent (in blocks) of the cube scanned at kill time for the village/water +1 bonus. */
        public final ModConfigSpec.IntValue villageWaterRadius;
        /**
         * Per-day weight delta indexed by 0..4 conditions met. List must be
         * exactly length 5; the loader validates length on first read.
         */
        public final ModConfigSpec.ConfigValue<List<? extends Integer>> deltaByConditions;

        Server(ModConfigSpec.Builder b) {
            b.comment("Gameplay tuning for Animal Weights — server-authoritative.").push("habitat");
            evalRadius = b.comment(
                    "Half-extent (in blocks) of the cube searched around a mob during the dawn evaluation."
            ).defineInRange("eval_radius_blocks", 8, 1, 32);
            lightThreshold = b.comment(
                    "Block-light level (0-15) considered bright enough to satisfy the \"light source nearby\" condition."
            ).defineInRange("light_threshold", 14, 0, 15);
            minRoamingCells = b.comment(
                    "Minimum count of walkable cells reachable via BFS for the \"stretching room\" condition to pass."
            ).defineInRange("min_roaming_cells", 6, 0, 64);
            roamingMaxBfsCells = b.comment(
                    "Hard cap on cells the reachability BFS will enumerate before terminating. Bounds compute cost per mob per dawn evaluation."
            ).defineInRange("roaming_max_bfs_cells", 32, 1, 256);
            b.pop();

            b.push("drops");
            sickDropRemovalChance = b.comment(
                    "Probability (0.0-1.0) that each primary drop from a sick (weight=0) mob is removed entirely instead of being capped to a single item."
            ).defineInRange("sick_drop_removal_chance", 0.5D, 0.0D, 1.0D);
            villageWaterRadius = b.comment(
                    "Half-extent (in blocks) of the cube searched around a killed animal when evaluating the village/water +1 drop bonus."
            ).defineInRange("village_water_radius", 6, 0, 16);
            b.pop();

            b.push("evaluation");
            deltaByConditions = b.comment(
                    "Per-day weight delta indexed by 0..4 conditions met. Must be length 5; out-of-range entries are clamped to the [-8, 8] sanity window."
            ).defineList(
                    "delta_by_conditions",
                    List.of(-2, -2, -1, 0, 1),
                    () -> 0,
                    o -> o instanceof Integer i && i >= -8 && i <= 8);
            b.pop();
        }
    }

    /**
     * Client-side visual tuning. Lives only on the client; never synced.
     */
    public static final class Client {
        /** ARGB tint applied to sick mobs by {@code LivingEntityRendererMixin}. */
        public final ModConfigSpec.IntValue sickTintArgb;
        /** Master toggle for the sick body tint. */
        public final ModConfigSpec.BooleanValue enableSickTint;

        Client(ModConfigSpec.Builder b) {
            b.comment("Visual tuning for Animal Weights — client-only.").push("sick_tell");
            sickTintArgb = b.comment(
                    "ARGB tint applied to sick (weight=0) target-species mobs. Default 0xFF814F30 (-8302800 decimal) is a desaturated brown."
            ).defineInRange("tint_argb", 0xFF814F30, Integer.MIN_VALUE, Integer.MAX_VALUE);
            enableSickTint = b.comment(
                    "If false, sick mobs receive no render tint — the mixin returns the vanilla default."
            ).define("enable_sick_tint", true);
            b.pop();
        }
    }

    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        Pair<Server, ModConfigSpec> serverPair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();

        Pair<Client, ModConfigSpec> clientPair = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();
    }

    /**
     * Look up {@link Server#deltaByConditions} as a properly-typed {@code int[5]}.
     * The TOML loader hands back {@code List<? extends Integer>} (could be
     * {@code Long} on disk-read in some cases); this helper coerces to
     * primitive {@code int} and validates length.
     *
     * <p>Falls back to the canonical {@code {-2, -2, -1, 0, 1}} default if the
     * configured list isn't exactly length 5. The default mirrors the
     * locked-in delta table from {@code design.md}.
     */
    public static int[] deltaByConditions() {
        List<? extends Integer> raw = SERVER.deltaByConditions.get();
        if (raw == null || raw.size() != 5) {
            return new int[] { -2, -2, -1, 0, 1 };
        }
        int[] out = new int[5];
        for (int i = 0; i < 5; i++) {
            out[i] = raw.get(i);
        }
        return out;
    }
}
