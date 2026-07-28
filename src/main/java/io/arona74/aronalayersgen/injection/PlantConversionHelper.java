package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.PlantMappingRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;

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
    public static void convertPlantsAboveLayers(ChunkAccess chunk) {
        // Plant conversion requires Conquest Reforged backend
        if (!LayerPlacementHelper.isConquestReforged()) {
            return;
        }

        PlantMappingRegistry registry = getPlantRegistry();
        int converted = 0;
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                int topY = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(localX, localZ);

                if (topY <= Compat.minY(chunk) + 2) continue;

                BlockPos plantPos = new BlockPos(worldX, topY - 1, worldZ);
                BlockState plantState = chunk.getBlockState(plantPos);
                Block plantBlock = plantState.getBlock();

                if (!registry.isReplaceablePlant(plantBlock)) continue;
                if (!LayerConfig.REPLACE_SEA_GRASS && isSeagrass(plantBlock)) continue;

                if (LayerConfig.logPlants()) {
                    AronaLayersGen.LOGGER.info("[Plant Debug] Found plant {} at {}",
                        Compat.blockId(plantBlock), plantPos);
                }

                BlockPos upperPos = null;
                if (plantState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && plantState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                    upperPos = plantPos;
                    plantPos = plantPos.below();
                    plantState = chunk.getBlockState(plantPos);
                    plantBlock = plantState.getBlock();
                }

                BlockPos belowPlant = plantPos.below();
                BlockState belowState = chunk.getBlockState(belowPlant);

                if (!RTFLayerInjector.hasLayerProperty(belowState)) continue;
                // CR foliage/rock blocks also carry the "layer" property — verify the block
                // is actually a registered layer block, not a decorator placed on top of the layer.
                if (!LayerPlacementHelper.getMappingRegistry().isLayerBlock(belowState.getBlock())) {
                    // Orphaned vanilla plant above a CR foliage/decorator — clear it.
                    // This happens when carvers or RTF terrain adjustments removed the solid block
                    // that originally supported the vanilla feature-placed plant.
                    Compat.chunkSetBlockState(chunk, plantPos, Blocks.AIR.defaultBlockState());
                    if (upperPos != null) {
                        Compat.chunkSetBlockState(chunk, upperPos, Blocks.AIR.defaultBlockState());
                    }
                    converted++;
                    continue;
                }

                Block conquestPlant = registry.getConquestPlant(plantBlock);
                if (conquestPlant == null) {
                    if (LayerConfig.logPlants()) {
                        AronaLayersGen.LOGGER.warn("[Plant Debug] No conquest mapping found for {}",
                            Compat.blockId(plantBlock));
                    }
                    continue;
                }

                if (LayerConfig.logPlants()) {
                    AronaLayersGen.LOGGER.info("[Plant Debug] Converting {} to {}, upperPos={}",
                        Compat.blockId(plantBlock),
                        Compat.blockId(conquestPlant),
                        upperPos);
                }

                int layerCount = RTFLayerInjector.readLayerCount(belowState);

                if (upperPos != null) {
                    BlockState conquestLower = conquestPlant.defaultBlockState();
                    conquestLower = RTFLayerInjector.applyLayerCount(conquestLower, conquestPlant, layerCount);
                    if (conquestLower.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                        conquestLower = conquestLower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    }
                    Compat.chunkSetBlockState(chunk, plantPos, conquestLower);

                    if (conquestPlant.defaultBlockState().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                        // CR equivalent is also tall — place upper half
                        BlockState conquestUpper = conquestPlant.defaultBlockState();
                        conquestUpper = RTFLayerInjector.applyLayerCount(conquestUpper, conquestPlant, layerCount);
                        conquestUpper = conquestUpper.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                        Compat.chunkSetBlockState(chunk, upperPos, conquestUpper);
                    } else {
                        // CR equivalent is a single-block plant — clear the orphaned vanilla upper half
                        Compat.chunkSetBlockState(chunk, upperPos, Blocks.AIR.defaultBlockState());
                    }
                } else {
                    BlockState conquestState = conquestPlant.defaultBlockState();
                    conquestState = RTFLayerInjector.applyLayerCount(conquestState, conquestPlant, layerCount);
                    Compat.chunkSetBlockState(chunk, plantPos, conquestState);
                }

                converted++;
            }
        }

        if (converted > 0 && LayerConfig.logPlants()) {
            AronaLayersGen.LOGGER.info("[Plant Conversion] ChunkAccess {},{}: converted {} plants",
                chunk.getPos().x, chunk.getPos().z, converted);
        }
    }
}
