package io.arona74.aronalayersgen.fabric;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.ModBlocks;
import net.fabricmc.api.ModInitializer;

/** Fabric entry point. Fabric keeps the registries open during initialization. */
public class AronaLayersGenFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ModBlocks.register();
        AronaLayersGen.init();
    }
}
