package io.github.josephosullivan.animalweights;

import com.mojang.logging.LogUtils;
import io.github.josephosullivan.animalweights.gametest.ModGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(AnimalWeights.MOD_ID)
public class AnimalWeights {
    public static final String MOD_ID = "animalweights";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AnimalWeights(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Animal Weights initializing");
        AnimalWeightAttachment.register(modEventBus);
        // Run 002: Tier-2 GameTest functions. Registered onto the mod event bus
        // so the runGameTestServer launcher picks them up via the
        // BuiltInRegistries.TEST_FUNCTION registry.
        ModGameTests.register(modEventBus);

        // Configs (run-006 task-1). Server config is gameplay tuning
        // (eval radius, light threshold, BFS cap, drop scaling, ...); client
        // config is visual tuning (sick mob tint). Save-format-coupled
        // constants (WEIGHT_MIN / MAX / DEFAULT) stay in code — see
        // AnimalWeightsTuning javadoc for the rationale.
        modContainer.registerConfig(ModConfig.Type.SERVER, AnimalWeightsConfig.SERVER_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, AnimalWeightsConfig.CLIENT_SPEC);
    }
}
