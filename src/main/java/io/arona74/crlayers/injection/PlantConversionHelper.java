package io.arona74.crlayers.injection;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import io.arona74.crlayers.PlantMappingRegistry;
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
 */
public class PlantConversionHelper {

    private static PlantMappingRegistry plantRegistry;

    /**
     * Check if a block is seagrass or tall_seagrass.
     */
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
     *
     * After FEATURES with the PlantBlockMixin active, the layout is:
     *   plantPos  = vanilla plant (placed by feature above the layer)
     *   layerPos  = layer block
     *   surfacePos = actual surface (grass_block, dirt, etc.)
     *
     * This method finds vanilla plants, checks if there's a layer below,
     * and replaces them with their conquest equivalents.
     */
    public static void convertPlantsAboveLayers(Chunk chunk) {
        PlantMappingRegistry registry = getPlantRegistry();
        int converted = 0;
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                // WORLD_SURFACE includes all non-air blocks (plants, layers, etc.)
                // MOTION_BLOCKING would miss short plants (grass, flowers) since they
                // don't block motion, causing the scan to land on the layer instead.
                int topY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(localX, localZ);

                if (topY <= chunk.getBottomY() + 2) continue;

                // The topmost block is at topY - 1
                BlockPos plantPos = new BlockPos(worldX, topY - 1, worldZ);
                BlockState plantState = chunk.getBlockState(plantPos);
                Block plantBlock = plantState.getBlock();

                // Check if this is a vanilla plant with a conquest mapping
                if (!registry.isReplaceablePlant(plantBlock)) {
                    continue;
                }

                // Skip seagrass conversion unless replace_sea_grass is enabled
                if (!LayerConfig.REPLACE_SEA_GRASS && isSeagrass(plantBlock)) {
                    continue;
                }

                // Debug logging for plant conversion
                if (LayerConfig.DEBUG_LOGGING) {
                    CRLayers.LOGGER.info("[Plant Debug] Found plant {} at {}",
                        net.minecraft.registry.Registries.BLOCK.getId(plantBlock), plantPos);
                }

                // For tall plants, the heightmap points at the upper half.
                // Walk down to the lower half so we find the layer below it.
                // Layout: upper (topY-1) → lower (topY-2) → layer (topY-3)
                BlockPos upperPos = null;
                if (plantState.contains(Properties.DOUBLE_BLOCK_HALF)
                        && plantState.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                    upperPos = plantPos;
                    plantPos = plantPos.down();
                    plantState = chunk.getBlockState(plantPos);
                    plantBlock = plantState.getBlock();
                }

                // Check if there's a layer block below the (lower) plant
                BlockPos belowPlant = plantPos.down();
                BlockState belowState = chunk.getBlockState(belowPlant);

                if (!RTFLayerInjector.hasLayerProperty(belowState)) {
                    continue;
                }

                // Convert vanilla plant to conquest equivalent
                Block conquestPlant = registry.getConquestPlant(plantBlock);
                if (conquestPlant == null) {
                    if (LayerConfig.DEBUG_LOGGING) {
                        CRLayers.LOGGER.warn("[Plant Debug] No conquest mapping found for {}",
                            net.minecraft.registry.Registries.BLOCK.getId(plantBlock));
                    }
                    continue;
                }

                if (LayerConfig.DEBUG_LOGGING) {
                    CRLayers.LOGGER.info("[Plant Debug] Converting {} to {}, upperPos={}",
                        net.minecraft.registry.Registries.BLOCK.getId(plantBlock),
                        net.minecraft.registry.Registries.BLOCK.getId(conquestPlant),
                        upperPos);
                }

                // Read the layer count from the layer block below so the conquest
                // plant gets the same value on its own layer property
                int layerCount = RTFLayerInjector.readLayerCount(belowState);

                if (upperPos != null) {
                    // Tall plant: convert both halves
                    BlockState conquestLower = conquestPlant.getDefaultState();
                    conquestLower = RTFLayerInjector.applyLayerCount(conquestLower, conquestPlant, layerCount);
                    if (conquestLower.contains(Properties.DOUBLE_BLOCK_HALF)) {
                        conquestLower = conquestLower.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    }
                    chunk.setBlockState(plantPos, conquestLower, false);

                    BlockState conquestUpper = conquestPlant.getDefaultState();
                    conquestUpper = RTFLayerInjector.applyLayerCount(conquestUpper, conquestPlant, layerCount);
                    if (conquestUpper.contains(Properties.DOUBLE_BLOCK_HALF)) {
                        conquestUpper = conquestUpper.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                    }
                    chunk.setBlockState(upperPos, conquestUpper, false);
                } else {
                    // Single-height plant: replace and apply matching layer count
                    BlockState conquestState = conquestPlant.getDefaultState();
                    conquestState = RTFLayerInjector.applyLayerCount(conquestState, conquestPlant, layerCount);
                    chunk.setBlockState(plantPos, conquestState, false);
                }

                converted++;
            }
        }

        if (converted > 0 && LayerConfig.DEBUG_LOGGING) {
            CRLayers.LOGGER.info("[Plant Conversion] Chunk {},{}: converted {} plants",
                chunk.getPos().x, chunk.getPos().z, converted);
        }
    }
}
