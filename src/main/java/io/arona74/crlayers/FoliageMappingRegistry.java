package io.arona74.crlayers;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for mapping surface blocks to extra foliage (conquest plants)
 * to scatter above layers.
 */
public class FoliageMappingRegistry {
    private final Map<Block, String> surfaceToFoliageMapping;

    public FoliageMappingRegistry() {
        this.surfaceToFoliageMapping = new HashMap<>();
        registerDefaultMappings();
    }

    private void registerDefaultMappings() {
        Map<String, String> configMappings = ConfigLoader.loadMappings("extra_foliage_mappings.json");

        for (Map.Entry<String, String> entry : configMappings.entrySet()) {
            Identifier vanillaId = Identifier.tryParse(entry.getKey());
            if (vanillaId == null) {
                CRLayers.LOGGER.warn("Invalid surface block ID in foliage config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = Registries.BLOCK.get(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                CRLayers.LOGGER.warn("Surface block not found for foliage mapping: {}", entry.getKey());
                continue;
            }

            surfaceToFoliageMapping.put(vanillaBlock, entry.getValue());
        }

        CRLayers.LOGGER.info("Registered {} extra foliage mappings from config", surfaceToFoliageMapping.size());
    }

    /**
     * Check if a foliage mapping exists for a surface block
     */
    public boolean hasMapping(Block surfaceBlock) {
        return surfaceToFoliageMapping.containsKey(surfaceBlock);
    }

    /**
     * Get the conquest foliage block for a given surface block.
     * @return The foliage block, or null if no mapping or block not found
     */
    public Block getFoliageBlock(Block surfaceBlock) {
        String foliageBlockId = surfaceToFoliageMapping.get(surfaceBlock);
        if (foliageBlockId == null) {
            return null;
        }

        Identifier identifier = Identifier.tryParse(foliageBlockId);
        if (identifier == null) {
            CRLayers.LOGGER.warn("Invalid foliage block identifier: {}", foliageBlockId);
            return null;
        }

        Block foliageBlock = Registries.BLOCK.get(identifier);
        if (foliageBlock == Blocks.AIR) {
            CRLayers.LOGGER.warn("Foliage block not found: {}. Is Conquest Reforged installed?", foliageBlockId);
            return null;
        }

        return foliageBlock;
    }
}
