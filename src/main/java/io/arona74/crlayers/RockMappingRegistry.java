package io.arona74.crlayers;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for mapping surface blocks to their Conquest Reforged rock equivalents.
 * Rocks are placed above layers with configurable chance and density.
 */
public class RockMappingRegistry {
    private final Map<Block, String> surfaceToRockMapping;

    public RockMappingRegistry() {
        this.surfaceToRockMapping = new HashMap<>();
        registerDefaultMappings();
    }

    /**
     * Load rock mappings from config file
     */
    private void registerDefaultMappings() {
        Map<String, String> configMappings = ConfigLoader.loadMappings("rock_mappings.json");

        for (Map.Entry<String, String> entry : configMappings.entrySet()) {
            Identifier vanillaId = Identifier.tryParse(entry.getKey());
            if (vanillaId == null) {
                CRLayers.LOGGER.warn("Invalid surface block ID in rock config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = Registries.BLOCK.get(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                CRLayers.LOGGER.warn("Surface block not found for rock mapping: {}", entry.getKey());
                continue;
            }

            surfaceToRockMapping.put(vanillaBlock, entry.getValue());
        }

        CRLayers.LOGGER.info("Registered {} rock mappings from config", surfaceToRockMapping.size());
    }

    /**
     * Check if a rock mapping exists for a surface block
     */
    public boolean hasMapping(Block surfaceBlock) {
        return surfaceToRockMapping.containsKey(surfaceBlock);
    }

    /**
     * Get the Conquest Reforged rock block for a given surface block.
     * @return The rock block, or null if no mapping or block not found
     */
    public Block getRockBlock(Block surfaceBlock) {
        String rockBlockId = surfaceToRockMapping.get(surfaceBlock);
        if (rockBlockId == null) {
            return null;
        }

        Identifier identifier = Identifier.tryParse(rockBlockId);
        if (identifier == null) {
            CRLayers.LOGGER.warn("Invalid rock block identifier: {}", rockBlockId);
            return null;
        }

        Block rockBlock = Registries.BLOCK.get(identifier);
        if (rockBlock == Blocks.AIR) {
            CRLayers.LOGGER.warn("Rock block not found: {}. Is Conquest Reforged installed?", rockBlockId);
            return null;
        }

        return rockBlock;
    }
}
