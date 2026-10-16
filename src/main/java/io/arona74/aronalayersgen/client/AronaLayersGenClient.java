package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

public class AronaLayersGenClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        if (!LayerConfig.PLANT_GROUNDING) return;
        warnIfRendererLacksFrapi();
        ModelLoadingPlugin.register(new GroundedPlantModelPlugin());
    }

    /**
     * The visual half of plant grounding is a Fabric Rendering API model. Vanilla's renderer (Indigo)
     * and Sodium 0.6+ implement that API; Sodium 0.5 does not without Indium, and then plants are
     * drawn where they were while their outline and collision are lowered. Warn rather than refuse
     * to start: grounding is one feature of this mod, not a reason to stop the game.
     */
    private static void warnIfRendererLacksFrapi() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded("indium")) return;
        loader.getModContainer("sodium").ifPresent(sodium -> {
            try {
                if (!VersionPredicate.parse(">=0.6.0").test(sodium.getMetadata().getVersion())) {
                    AronaLayersGen.LOGGER.warn("Sodium {} without Indium cannot draw grounded plants lowered: "
                        + "install Indium (https://modrinth.com/mod/indium), or set plant_grounding_shapes "
                        + "to false so outlines stay where the plants are drawn.",
                        sodium.getMetadata().getVersion().getFriendlyString());
                }
            } catch (VersionParsingException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
