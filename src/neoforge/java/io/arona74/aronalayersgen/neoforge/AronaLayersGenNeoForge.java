package io.arona74.aronalayersgen.neoforge;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * NeoForge entry point. ModBlocks registers straight into the vanilla registries, which NeoForge
 * only allows while it fires RegisterEvent, so it runs from the event for blocks. The rest of the
 * setup only adds event listeners and reads the config, so it runs here. ClientSetup is each
 * Minecraft version's own.
 */
@Mod(AronaLayersGen.MOD_ID)
public class AronaLayersGenNeoForge {
    public AronaLayersGenNeoForge(IEventBus modBus, ModContainer container, Dist dist) {
        modBus.addListener(RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(Registries.BLOCK)) {
                ModBlocks.register();
            }
        });
        AronaLayersGen.init();

        if (dist.isClient()) {
            ClientSetup.init(modBus, container);
        }
    }
}
