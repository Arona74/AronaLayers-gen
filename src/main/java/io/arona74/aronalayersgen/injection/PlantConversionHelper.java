package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.PlantMappingRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

/**
 * Converts vanilla plants above layer blocks to their Conquest Reforged equivalents.
 * Called during the WorldChunk correction pass after all generation stages complete.
 * Only active when Conquest Reforged is the backend.
 */
public class PlantConversionHelper {

    private static PlantMappingRegistry plantRegistry;

    private static boolean isSeagrass(Block block) {
        return block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS;
    }

    private static PlantMappingRegistry getPlantRegistry() {
        if (plantRegistry == null) {
            plantRegistry = new PlantMappingRegistry();
        }
        return plantRegistry;
    }

    /**
     * Scan a chunk for vanilla plants above layer blocks and convert them
     * to conquest equivalents.
     */
    public static void convertPlantsAboveLayers(Chunk chunk) {
        // Plant conversion requires Conquest Reforged backend
        if (!LayerPlacementHelper.isConquestReforged()) {
            return;
        }

        PlantMappingRegistry registry = getPlantRegistry();
        int converted = 0;
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                int topY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(localX, localZ);

                if (topY <= chunk.getBottomY() + 2) continue;

                BlockPos plantPos = new BlockPos(worldX, topY - 1, worldZ);
                BlockState plantState = chunk.getBlockState(plantPos);
                Block plantBlock = plantState.getBlock();

                if (!registry.isReplaceablePlant(plantBlock)) continue;
                if (!LayerConfig.REPLACE_SEA_GRASS && isSeagrass(plantBlock)) continue;

                if (LayerConfig.logPlants()) {
                    AronaLayersGen.LOGGER.info("[Plant Debug] Found plant {} at {}",
                        net.minecraft.registry.Registries.BLOCK.getId(plantBlock), plantPos);
                }

                BlockPos upperPos = null;
                if (plantState.contains(Properties.DOUBLE_BLOCK_HALF)
                        && plantState.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                    upperPos = plantPos;
                    plantPos = plantPos.down();
                    plantState = chunk.getBlockState(plantPos);
                    plantBlock = plantState.getBlock();
                }

                BlockPos belowPlant = plantPos.down();
                BlockState belowState = chunk.getBlockState(belowPlant);

                if (!RTFLayerInjector.hasLayerProperty(belowState)) continue;
                // CR foliage/rock blocks also carry the "layer" property — verify the block
                // is actually a registered layer block, not a decorator placed on top of the layer.
                if (!LayerPlacementHelper.getMappingRegistry().isLayerBlock(belowState.getBlock())) {
                    // Orphaned vanilla plant above a CR foliage/decorator — clear it.
                    // This happens when carvers or RTF terrain adjustments removed the solid block
                    // that originally supported the vanilla feature-placed plant.
                    chunk.setBlockState(plantPos, Blocks.AIR.getDefaultState(), false);
                    if (upperPos != null) {
                        chunk.setBlockState(upperPos, Blocks.AIR.getDefaultState(), false);
                    }
                    converted++;
                    continue;
                }

                Block conquestPlant = registry.getConquestPlant(plantBlock);
                if (conquestPlant == null) {
                    if (LayerConfig.logPlants()) {
                        AronaLayersGen.LOGGER.warn("[Plant Debug] No conquest mapping found for {}",
                            net.minecraft.registry.Registries.BLOCK.getId(plantBlock));
                    }
                    continue;
                }

                if (LayerConfig.logPlants()) {
                    AronaLayersGen.LOGGER.info("[Plant Debug] Converting {} to {}, upperPos={}",
                        net.minecraft.registry.Registries.BLOCK.getId(plantBlock),
                        net.minecraft.registry.Registries.BLOCK.getId(conquestPlant),
                        upperPos);
                }

                int layerCount = RTFLayerInjector.readLayerCount(belowState);

                if (upperPos != null) {
                    BlockState conquestLower = conquestPlant.getDefaultState();
                    conquestLower = RTFLayerInjector.applyLayerCount(conquestLower, conquestPlant, layerCount);
                    if (conquestLower.contains(Properties.DOUBLE_BLOCK_HALF)) {
                        conquestLower = conquestLower.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    }
                    chunk.setBlockState(plantPos, conquestLower, false);

                    if (conquestPlant.getDefaultState().contains(Properties.DOUBLE_BLOCK_HALF)) {
                        // CR equivalent is also tall — place upper half
                        BlockState conquestUpper = conquestPlant.getDefaultState();
                        conquestUpper = RTFLayerInjector.applyLayerCount(conquestUpper, conquestPlant, layerCount);
                        conquestUpper = conquestUpper.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                        chunk.setBlockState(upperPos, conquestUpper, false);
                    } else {
                        // CR equivalent is a single-block plant — clear the orphaned vanilla upper half
                        chunk.setBlockState(upperPos, Blocks.AIR.getDefaultState(), false);
                    }
                } else {
                    BlockState conquestState = conquestPlant.getDefaultState();
                    conquestState = RTFLayerInjector.applyLayerCount(conquestState, conquestPlant, layerCount);
                    chunk.setBlockState(plantPos, conquestState, false);
                }

                converted++;
            }
        }

        if (converted > 0 && LayerConfig.logPlants()) {
            AronaLayersGen.LOGGER.info("[Plant Conversion] Chunk {},{}: converted {} plants",
                chunk.getPos().x, chunk.getPos().z, converted);
        }
    }
}
