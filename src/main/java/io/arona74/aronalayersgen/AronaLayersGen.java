package io.arona74.aronalayersgen;

import io.arona74.aronalayersgen.command.ChunkDebugCommand;
import io.arona74.aronalayersgen.command.TellusDebugCommand;
import io.arona74.aronalayersgen.grounding.GroundingFlags;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import io.arona74.aronalayersgen.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent setup. Each loader's entry point (fabric.AronaLayersGenFabric,
 * neoforge.AronaLayersGenNeoForge) calls {@link ModBlocks#register()} when its block registry is
 * open, then {@link #init()}.
 */
public final class AronaLayersGen {
    private AronaLayersGen() {}

    public static final String MOD_ID = "aronalayersgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initializing Arona Layers Generator");

        Platform.registerCommands(dispatcher -> {
            ChunkDebugCommand.register(dispatcher);
            TellusDebugCommand.register(dispatcher);
        });

        // Re-map layers whose RTF surface was swapped to beach/river sand after POST_FEATURES
        // injection; runs at chunk-load so it catches spawn/cross-border chunks the in-init pass misses.
        Platform.onChunkLoad(RTFLayerInjector::onChunkLoadRemap);

        // Plant grounding answers "not grounded" until now: shapes are queried during vanilla's
        // block bootstrap, before the config can safely load (see GroundingFlags).
        GroundingFlags.ready = true;

        LOGGER.info("Arona Layers Generator initialized successfully");
        LOGGER.info("[Config] layer_injection={} plant_injection={} tree_injection={}"
            + " (each dimension's layer source is detected when it first generates)",
            LayerConfig.LAYER_INJECTION, LayerConfig.PLANT_INJECTION, LayerConfig.TREE_INJECTION);
    }
}
