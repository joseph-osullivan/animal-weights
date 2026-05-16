package io.github.josephosullivan.animalweights;

import com.mojang.logging.LogUtils;
import io.github.josephosullivan.animalweights.gametest.ModGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AnimalWeights.MOD_ID)
public class AnimalWeights {
    public static final String MOD_ID = "animalweights";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AnimalWeights(IEventBus modEventBus) {
        LOGGER.info("Animal Weights initializing");
        AnimalWeightAttachment.register(modEventBus);
        // Run 002: Tier-2 GameTest functions. Registered onto the mod event bus
        // so the runGameTestServer launcher picks them up via the
        // BuiltInRegistries.TEST_FUNCTION registry.
        ModGameTests.register(modEventBus);
    }
}
