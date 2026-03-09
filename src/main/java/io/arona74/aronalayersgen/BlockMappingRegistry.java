package io.arona74.aronalayersgen;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for mapping vanilla blocks to their layer block equivalents.
 * Supports both Conquest Reforged (cr_block_mappings.json) and
 * VanillaLayerPlus (vp_block_mappings.json) backends.
 */
public class BlockMappingRegistry {
    private final Map<Block, String> blockToLayerMapping;
    private final String configFileName;

    public BlockMappingRegistry(String configFileName) {
        this.configFileName = configFileName;
        this.blockToLayerMapping = new HashMap<>();
        registerDefaultMappings();
    }

    private void registerDefaultMappings() {
        Map<String, String> configMappings = ConfigLoader.loadMappings(configFileName);

        for (Map.Entry<String, String> entry : configMappings.entrySet()) {
            Identifier vanillaId = Identifier.tryParse(entry.getKey());
            if (vanillaId == null) {
                AronaLayersGen.LOGGER.warn("Invalid vanilla block ID in config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = Registries.BLOCK.get(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                AronaLayersGen.LOGGER.warn("Vanilla block not found: {}", entry.getKey());
                continue;
            }

            blockToLayerMapping.put(vanillaBlock, entry.getValue());
        }

        AronaLayersGen.LOGGER.info("Registered {} block-to-layer mappings from {}", blockToLayerMapping.size(), configFileName);
    }

    /**
     * Get the layer block for a given vanilla block.
     * @param vanillaBlock The vanilla block
     * @param layerCount The number of layers (used for logging only; block selection is by surface type)
     * @return The layer block, or null if no mapping exists or block not found
     */
    public Block getLayerBlock(Block vanillaBlock, int layerCount) {
        String layerBlockId = blockToLayerMapping.get(vanillaBlock);

        if (layerBlockId == null) {
            return null;
        }

        Identifier identifier = Identifier.tryParse(layerBlockId);
        if (identifier == null) {
            AronaLayersGen.LOGGER.warn("Invalid block identifier: {}", layerBlockId);
            return null;
        }

        Block layerBlock = Registries.BLOCK.get(identifier);

        if (layerBlock == Blocks.AIR) {
            AronaLayersGen.LOGGER.warn("Layer block not found in registry: {}. Is the required mod installed?", layerBlockId);
            return null;
        }

        return layerBlock;
    }

    public boolean hasMapping(Block block) {
        return blockToLayerMapping.containsKey(block);
    }
}
