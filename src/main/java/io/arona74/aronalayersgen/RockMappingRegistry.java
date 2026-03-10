package io.arona74.aronalayersgen;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for mapping surface blocks to their Conquest Reforged rock equivalents.
 * Rocks are placed above layers with configurable chance and density.
 * Only used when Conquest Reforged is the active backend.
 */
public class RockMappingRegistry {
    private final Map<Block, String> surfaceToRockMapping;

    public RockMappingRegistry() {
        this.surfaceToRockMapping = new HashMap<>();
        registerDefaultMappings();
    }

    private void registerDefaultMappings() {
        Map<String, String> configMappings = ConfigLoader.loadMappings("cr_rock_mappings.json");

        for (Map.Entry<String, String> entry : configMappings.entrySet()) {
            Identifier vanillaId = Identifier.tryParse(entry.getKey());
            if (vanillaId == null) {
                AronaLayersGen.LOGGER.warn("Invalid surface block ID in rock config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = Registries.BLOCK.get(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                AronaLayersGen.LOGGER.warn("Surface block not found for rock mapping: {}", entry.getKey());
                continue;
            }

            surfaceToRockMapping.put(vanillaBlock, entry.getValue());
        }

        AronaLayersGen.LOGGER.info("Registered {} rock mappings from config", surfaceToRockMapping.size());
    }

    public boolean hasMapping(Block surfaceBlock) {
        return surfaceToRockMapping.containsKey(surfaceBlock);
    }

    public Block getRockBlock(Block surfaceBlock) {
        String rockBlockId = surfaceToRockMapping.get(surfaceBlock);
        if (rockBlockId == null) {
            return null;
        }

        Identifier identifier = Identifier.tryParse(rockBlockId);
        if (identifier == null) {
            AronaLayersGen.LOGGER.warn("Invalid rock block identifier: {}", rockBlockId);
            return null;
        }

        Block rockBlock = Registries.BLOCK.get(identifier);
        if (rockBlock == Blocks.AIR) {
            AronaLayersGen.LOGGER.warn("Rock block not found: {}. Is Conquest Reforged installed?", rockBlockId);
            return null;
        }

        return rockBlock;
    }
}
