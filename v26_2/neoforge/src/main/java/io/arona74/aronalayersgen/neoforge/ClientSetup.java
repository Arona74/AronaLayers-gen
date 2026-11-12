package io.arona74.aronalayersgen.neoforge;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.client.GroundedPlantModelPlugin;
import io.arona74.aronalayersgen.client.config.AronaConfigScreen;
import io.arona74.aronalayersgen.client.config.ScreenCompat;
import io.arona74.aronalayersgen.platform.ClientPlatform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** NeoForge, Minecraft 26.2: client setup, called by the entry point on the client only. */
public final class ClientSetup {
    private ClientSetup() {}

    public static void init(IEventBus modBus, ModContainer container) {
        // The mod list's "Config" button.
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, parent) -> new AronaConfigScreen(parent));

        // /algconfig opens the config screen. Scheduled, because the chat screen the command was typed
        // in closes itself after running it and would close ours with it.
        ClientPlatform.registerClientCommand("algconfig",
                () -> ScreenCompat.schedule(() -> ScreenCompat.setScreen(new AronaConfigScreen(null))));

        // The wrappers also carry the trunk, post and chorus stem models, which trees_on_layers and
        // posts_on_layers need even with grounding off.
        if (!LayerConfig.PLANT_GROUNDING && !LayerConfig.treesOnLayers() && !LayerConfig.postsOnLayers()) return;
        modBus.addListener(ModelEvent.ModifyBakingResult.class, GroundedPlantModelPlugin::wrapModels);
    }
}
