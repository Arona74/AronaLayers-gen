package io.arona74.aronalayersgen;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for mapping vanilla blocks to their layer block equivalents.
 * Supports both Conquest Reforged (cr_block_mappings.json) and
 * VanillaLayerPlus (vp_block_mappings.json) backends.
 */
public class BlockMappingRegistry {
    private final Map<Block, String> blockToLayerMapping;
    private final java.util.Set<Block> knownLayerBlocks;
    private final String configFileName;

    public BlockMappingRegistry(String configFileName) {
        this.configFileName = configFileName;
        this.blockToLayerMapping = new HashMap<>();
        this.knownLayerBlocks = new java.util.HashSet<>();
        registerDefaultMappings();
    }

    private void registerDefaultMappings() {
        Map<String, String> configMappings = ConfigLoader.loadMappings(configFileName);

        for (Map.Entry<String, String> entry : configMappings.entrySet()) {
            ResourceLocation vanillaId = ResourceLocation.tryParse(entry.getKey());
            if (vanillaId == null) {
                AronaLayersGen.LOGGER.warn("Invalid vanilla block ID in config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = BuiltInRegistries.BLOCK.get(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                AronaLayersGen.LOGGER.warn("Vanilla block not found: {}", entry.getKey());
                continue;
            }

            blockToLayerMapping.put(vanillaBlock, entry.getValue());

            // Track the resolved layer block so we can distinguish actual layer blocks
            // from foliage/rock blocks that also happen to have a "layer" property.
            ResourceLocation layerId = ResourceLocation.tryParse(entry.getValue());
            if (layerId != null) {
                Block layerBlock = BuiltInRegistries.BLOCK.get(layerId);
                if (layerBlock != Blocks.AIR) {
                    knownLayerBlocks.add(layerBlock);
                }
            }
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

        ResourceLocation identifier = ResourceLocation.tryParse(layerBlockId);
        if (identifier == null) {
            AronaLayersGen.LOGGER.warn("Invalid block identifier: {}", layerBlockId);
            return null;
        }

        Block layerBlock = BuiltInRegistries.BLOCK.get(identifier);

        if (layerBlock == Blocks.AIR) {
            AronaLayersGen.LOGGER.warn("Layer block not found in registry: {}. Is the required mod installed?", layerBlockId);
            return null;
        }

        return layerBlock;
    }

    public boolean hasMapping(Block block) {
        return blockToLayerMapping.containsKey(block);
    }

    /**
     * Returns true if the given block is a known layer block (i.e. appears as a value
     * in the surface→layer mapping). Used to distinguish layer blocks from other blocks
     * (e.g. CR foliage) that also happen to carry a "layer" integer property.
     */
    public boolean isLayerBlock(Block block) {
        return knownLayerBlocks.contains(block);
    }
}
