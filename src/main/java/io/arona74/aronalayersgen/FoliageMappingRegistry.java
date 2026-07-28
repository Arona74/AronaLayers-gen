package io.arona74.aronalayersgen;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for mapping surface blocks to extra foliage (conquest plants)
 * to scatter above layers.
 * Only used when Conquest Reforged is the active backend.
 */
public class FoliageMappingRegistry {
    private final Map<Block, String> surfaceToFoliageMapping;

    public FoliageMappingRegistry() {
        this.surfaceToFoliageMapping = new HashMap<>();
        registerDefaultMappings();
    }

    private void registerDefaultMappings() {
        Map<String, String> configMappings = ConfigLoader.loadMappings("cr_extra_foliage_mappings.json");

        for (Map.Entry<String, String> entry : configMappings.entrySet()) {
            ResourceLocation vanillaId = ResourceLocation.tryParse(entry.getKey());
            if (vanillaId == null) {
                AronaLayersGen.LOGGER.warn("Invalid surface block ID in foliage config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = BuiltInRegistries.BLOCK.get(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                AronaLayersGen.LOGGER.warn("Surface block not found for foliage mapping: {}", entry.getKey());
                continue;
            }

            surfaceToFoliageMapping.put(vanillaBlock, entry.getValue());
        }

        AronaLayersGen.LOGGER.info("Registered {} extra foliage mappings from config", surfaceToFoliageMapping.size());
    }

    public boolean hasMapping(Block surfaceBlock) {
        return surfaceToFoliageMapping.containsKey(surfaceBlock);
    }

    public Block getFoliageBlock(Block surfaceBlock) {
        String foliageBlockId = surfaceToFoliageMapping.get(surfaceBlock);
        if (foliageBlockId == null) {
            return null;
        }

        ResourceLocation identifier = ResourceLocation.tryParse(foliageBlockId);
        if (identifier == null) {
            AronaLayersGen.LOGGER.warn("Invalid foliage block identifier: {}", foliageBlockId);
            return null;
        }

        Block foliageBlock = BuiltInRegistries.BLOCK.get(identifier);
        if (foliageBlock == Blocks.AIR) {
            AronaLayersGen.LOGGER.warn("Foliage block not found: {}. Is Conquest Reforged installed?", foliageBlockId);
            return null;
        }

        return foliageBlock;
    }
}
