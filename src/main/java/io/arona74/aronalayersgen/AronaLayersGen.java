package io.arona74.aronalayersgen;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AronaLayersGen implements ModInitializer {
    public static final String MOD_ID = "aronalayersgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Arona Layers Generator");
        LOGGER.info("Arona Layers Generator initialized successfully");
    }
}
