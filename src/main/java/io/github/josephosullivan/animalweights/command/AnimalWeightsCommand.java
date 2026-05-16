package io.github.josephosullivan.animalweights.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.josephosullivan.animalweights.AnimalWeightAttachment;
import io.github.josephosullivan.animalweights.AnimalWeights;
import io.github.josephosullivan.animalweights.event.SickStateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /animalweights} command (run-005 task-3).
 *
 * <p>Two subcommands:
 * <ul>
 *   <li>{@code /animalweights} — no args. Rays out 5 blocks from the executing
 *       player and prints the looked-at target-species animal's weight,
 *       sick state, and species name to chat.</li>
 *   <li>{@code /animalweights set <0..8> [target]} — sets a target-species
 *       animal's weight via {@link AnimalWeightAttachment#set(LivingEntity, int)}
 *       (which clamps to {@code [WEIGHT_MIN, WEIGHT_MAX]}). The target argument
 *       is optional; without it, the looked-at entity is used. Brigadier itself
 *       rejects weights outside {@code [0, 8]} via
 *       {@link IntegerArgumentType#integer(int, int)} — so {@code /animalweights
 *       set 99} fails at parse time with the standard "integer out of range"
 *       message. That's intentional: the clamp helper exists for programmatic
 *       callers, the command takes the strict-input path.</li>
 * </ul>
 *
 * <p>Target species is the
 * {@link io.github.josephosullivan.animalweights.AnimalWeightsTags#TRACKED}
 * entity-type tag — by default cow, mooshroom, pig, sheep, chicken, rabbit;
 * extensible to modded animals via datapack.
 *
 * <p>Self-registers via {@link EventBusSubscriber} onto NeoForge's
 * {@link RegisterCommandsEvent}; no wiring needed in {@link AnimalWeights}.
 *
 * <p>Raycast: uses {@link ProjectileUtil#getEntityHitResult} (NOT
 * {@code Entity#pick}, which only hits blocks) so we actually find entities in
 * the player's line of sight. Filter on {@link AnimalWeightAttachment#isTracked}
 * so a looked-at zombie/villager/etc. doesn't shadow a target species
 * behind it.
 */
@EventBusSubscriber(modid = AnimalWeights.MOD_ID)
public final class AnimalWeightsCommand {

    /** 5-block reach — matches the task spec. */
    private static final double REACH_BLOCKS = 5.0D;

    private static final SimpleCommandExceptionType ERROR_NO_TARGET =
            new SimpleCommandExceptionType(Component.literal(
                    "No target-species animal in line of sight (cow/pig/sheep/chicken/rabbit)."));

    private static final SimpleCommandExceptionType ERROR_WRONG_SPECIES =
            new SimpleCommandExceptionType(Component.literal(
                    "Target is not a supported species (cow/pig/sheep/chicken/rabbit)."));

    private AnimalWeightsCommand() {
        // event handler — no instances
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("animalweights")
                .executes(AnimalWeightsCommand::runInfo)
                .then(Commands.literal("set")
                        .then(Commands.argument("weight", IntegerArgumentType.integer(0, 8))
                                .executes(AnimalWeightsCommand::runSetLookedAt)
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(AnimalWeightsCommand::runSetTargeted))));
        event.getDispatcher().register(root);
    }

    /**
     * {@code /animalweights} — print info about the looked-at target-species animal.
     */
    private static int runInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        LivingEntity target = raycastTargetSpecies(player);
        if (target == null) {
            throw ERROR_NO_TARGET.create();
        }
        int weight = AnimalWeightAttachment.get(target);
        boolean sick = SickStateHandler.isSick(weight);
        String species = speciesNameOf(target);
        source.sendSuccess(() -> Component.translatable(
                "animalweights.command.info",
                species,
                weight,
                sick ? Component.translatable("animalweights.command.sick.yes")
                     : Component.translatable("animalweights.command.sick.no")
        ), /* allowLogging */ false);
        return 1;
    }

    /**
     * {@code /animalweights set <weight>} — set the looked-at animal's weight.
     */
    private static int runSetLookedAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        LivingEntity target = raycastTargetSpecies(player);
        if (target == null) {
            throw ERROR_NO_TARGET.create();
        }
        return applySet(source, target, IntegerArgumentType.getInteger(ctx, "weight"));
    }

    /**
     * {@code /animalweights set <weight> <target>} — set the selector-resolved target's weight.
     */
    private static int runSetTargeted(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Entity raw = EntityArgument.getEntity(ctx, "target");
        if (!AnimalWeightAttachment.isTracked(raw) || !(raw instanceof LivingEntity living)) {
            throw ERROR_WRONG_SPECIES.create();
        }
        return applySet(source, living, IntegerArgumentType.getInteger(ctx, "weight"));
    }

    /**
     * Shared tail of the {@code set} subcommand: writes via the clamping helper
     * and feedbacks the actually-stored value (so callers see proof of clamp).
     */
    private static int applySet(CommandSourceStack source, LivingEntity target, int requestedWeight) {
        // AnimalWeightAttachment.set clamps to [0, 8] and returns the stored value.
        // Brigadier already rejects out-of-range via integer(0, 8) at parse time,
        // so under normal command-line input requested == stored, but we still
        // route through the helper to keep one canonical write path.
        int stored = AnimalWeightAttachment.set(target, requestedWeight);
        String species = speciesNameOf(target);
        source.sendSuccess(() -> Component.translatable(
                "animalweights.command.set",
                species,
                stored
        ), /* allowLogging */ true);
        return stored;
    }

    /**
     * Cast a 5-block ray from the player's eye along view vector; return the
     * nearest target-species animal in the line of sight, or null.
     *
     * <p>Uses {@link ProjectileUtil#getEntityHitResult} rather than
     * {@code Player#pick}: the latter returns a {@code BlockHitResult} and
     * doesn't even look at entities, which would be the wrong primitive here.
     */
    private static LivingEntity raycastTargetSpecies(ServerPlayer player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.x * REACH_BLOCKS, look.y * REACH_BLOCKS, look.z * REACH_BLOCKS);
        AABB searchBox = player.getBoundingBox().expandTowards(look.scale(REACH_BLOCKS)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player,
                eye,
                end,
                searchBox,
                AnimalWeightAttachment::isTracked,
                REACH_BLOCKS * REACH_BLOCKS);
        if (hit == null) {
            return null;
        }
        Entity entity = hit.getEntity();
        return entity instanceof LivingEntity living ? living : null;
    }

    /**
     * Human-readable species label for chat output. Reads the entity type's
     * short registry id ({@code cow}, {@code pig}, {@code rabbit_alex_mobs},
     * …) — works uniformly for vanilla and modded entries in the
     * {@link io.github.josephosullivan.animalweights.AnimalWeightsTags#TRACKED}
     * tag without an instanceof chain that would silently exclude modded
     * species.
     */
    private static String speciesNameOf(Entity entity) {
        return entity.getType().toShortString();
    }
}
