package io.arona74.aronalayersgen;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;

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
            String vanillaId = Compat.normalizeId(entry.getKey());
            if (vanillaId == null) {
                AronaLayersGen.LOGGER.warn("Invalid vanilla block ID in config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = Compat.blockFromId(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                // Mappings for other mods' blocks (create:zinc_ore) ship by default; their mod being
                // absent is normal, not a config error.
                if (vanillaId.startsWith("minecraft:")) {
                    AronaLayersGen.LOGGER.warn("Vanilla block not found: {}", entry.getKey());
                } else {
                    AronaLayersGen.LOGGER.debug("Skipping mapping for {} (its mod is not installed)", entry.getKey());
                }
                continue;
            }

            blockToLayerMapping.put(vanillaBlock, entry.getValue());

            // Track the resolved layer block so we can distinguish actual layer blocks
            // from foliage/rock blocks that also happen to have a "layer" property.
            String layerId = Compat.normalizeId(entry.getValue());
            if (layerId != null) {
                Block layerBlock = Compat.blockFromId(layerId);
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

        String identifier = Compat.normalizeId(layerBlockId);
        if (identifier == null) {
            AronaLayersGen.LOGGER.warn("Invalid block identifier: {}", layerBlockId);
            return null;
        }

        Block layerBlock = Compat.blockFromId(identifier);

        if (layerBlock == Blocks.AIR) {
            AronaLayersGen.LOGGER.warn("Layer block not found in registry: {}. Is the required mod installed?", layerBlockId);
            return null;
        }

        return layerBlock;
    }

    public boolean hasMapping(Block block) {
        return blockToLayerMapping.containsKey(block);
    }

    /** Layer block -> the block it was mapped from (first mapping wins), built on first use. */
    private volatile Map<Block, Block> layerToSource;

    /**
     * The block a layer block stands in for: the surface block mapped to it ({@code mud} for
     * {@code vanillalayerplus:mud_layer}). Null if no mapping produces this layer block. Used where
     * a layer has to be shown as a whole block, e.g. Distant Horizons LODs.
     */
    public Block getSourceBlock(Block layerBlock) {
        Map<Block, Block> map = layerToSource;
        if (map == null) {
            map = new HashMap<>();
            for (Map.Entry<Block, String> entry : blockToLayerMapping.entrySet()) {
                String id = Compat.normalizeId(entry.getValue());
                if (id == null) continue;
                Block layer = Compat.blockFromId(id);
                if (layer != Blocks.AIR) map.putIfAbsent(layer, entry.getKey());
            }
            layerToSource = map;
        }
        return map.get(layerBlock);
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
