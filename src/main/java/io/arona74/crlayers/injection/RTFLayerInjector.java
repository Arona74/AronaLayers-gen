package io.arona74.crlayers.injection;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Set;

/**
 * Layer injector that uses ReTerraForged terrain data.
 * Uses snow-layer logic with fractional terrain height for realistic layer placement.
 *
 * Delegates shared placement logic to LayerPlacementHelper.
 */
public class RTFLayerInjector {

    // ========== Public accessors (delegate to helper) ==========

    public static boolean hasMappingFor(Block surfaceBlock) {
        return LayerPlacementHelper.hasMappingFor(surfaceBlock);
    }

    public static Block getMappedLayerBlock(Block surfaceBlock, int layerCount) {
        return LayerPlacementHelper.getMappedLayerBlock(surfaceBlock, layerCount);
    }

    public static BlockState applyLayerCount(BlockState state, Block block, int layerCount) {
        return LayerPlacementHelper.applyLayerCount(state, block, layerCount);
    }

    public static int readLayerCount(BlockState state) {
        return LayerPlacementHelper.readLayerCount(state);
    }

    public static boolean hasLayerProperty(BlockState state) {
        return LayerPlacementHelper.hasLayerProperty(state);
    }

    // ========== RTF-specific: Layer Count Calculation ==========

    /**
     * Calculate layer count using snow-layer logic from RTF's DecorateSnowFeature.
     *
     * Logic (from RTF):
     * - depth = height * worldHeight - (int)(height * worldHeight)
     * - layers = round(depth * 8)
     * - Then we subtract 1 as per user request
     *
     * @param height RTF cell height (0-1 normalized)
     * @param worldHeight RTF world height from Levels
     * @param skipReduction If true, don't subtract 1 (used for snow layers)
     * @return Layer count (1-8, or 0 if no layer should be placed)
     */
    private static int calculateLayerCount(float height, int worldHeight, boolean skipReduction) {
        float scaledHeight = height * worldHeight;
        float depth = scaledHeight - (int) scaledHeight;
        int layers = Math.round(depth * 8);

        if (!skipReduction) {
            layers = layers - 1;
        }

        if (layers < 1) {
            return 0;
        }
        return Math.min(8, layers);
    }

    // ========== RTF-specific: Injection Entry Point ==========

    /**
     * Inject layers for a single position using RTF Cell data with snow-layer logic.
     * Calculates the layer count from RTF height data, then delegates to shared helper.
     *
     * @param chunk The chunk being generated
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param worldHeight RTF world height for scaling
     * @param height RTF cell height value (0-1 normalized)
     * @param isRiver True if this is a river position
     * @param isSubmerged True if terrain is underwater
     * @return true if a layer was placed
     */
    public static boolean injectLayerAt(Chunk chunk, int worldX, int worldZ, int worldHeight,
                                      float height, boolean isRiver, boolean isSubmerged) {
        // Skip rivers (unless underwater layers are enabled)
        if (isRiver && !LayerConfig.UNDERWATER_LAYERS) {
            debugSkipRiver++;
            return false;
        }

        // Skip underwater unless configured
        if (isSubmerged && !LayerConfig.UNDERWATER_LAYERS) {
            debugSkipSubmerged++;
            return false;
        }

        // Detect snowy biome for skipReduction parameter
        int localX = worldX & 15;
        int localZ = worldZ & 15;
        Heightmap.Type hmType = (chunk instanceof WorldChunk)
            ? Heightmap.Type.OCEAN_FLOOR : Heightmap.Type.OCEAN_FLOOR_WG;
        int surfaceY = chunk.getHeightmap(hmType).get(localX, localZ);

        boolean isSnowyBiome = false;
        if (surfaceY > chunk.getBottomY()) {
            BlockPos biomePos = new BlockPos(worldX, surfaceY - 1, worldZ);
            var biome = chunk.getBiomeForNoiseGen(localX >> 2, surfaceY >> 2, localZ >> 2);
            isSnowyBiome = biome.value().isCold(biomePos);
        }

        boolean useSnowLayers = isSnowyBiome && LayerConfig.IMPROVE_SNOWY_BIOMES;

        // Calculate layer count using RTF's fractional height
        int layerCount = calculateLayerCount(height, worldHeight, useSnowLayers);

        // Delegate to shared placement logic
        return LayerPlacementHelper.injectLayerAt(chunk, worldX, worldZ, layerCount, useSnowLayers);
    }

    // ========== RTF-specific: Chunk-level Processing ==========

    // Debug counters (RTF-specific: river, submerged)
    private static int debugSkipRiver = 0;
    private static int debugSkipSubmerged = 0;
    private static boolean debugLogged = false;

    /**
     * Process an entire chunk using RTF data with snow-layer logic.
     *
     * @param chunk The chunk to process
     * @param worldHeight The world height from RTF Levels
     * @param cellAccessor Function to access Cell data for a position
     */
    public static void injectLayersForChunk(Chunk chunk, int worldHeight, CellAccessor cellAccessor) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int layersPlaced = 0;
        int cellsProcessed = 0;
        int cellsNull = 0;

        // Reset debug counters
        debugSkipRiver = 0;
        debugSkipSubmerged = 0;
        LayerPlacementHelper.debugSkipSnowy = 0;
        LayerPlacementHelper.debugSkipNoSurface = 0;
        LayerPlacementHelper.debugSkipNoMapping = 0;
        LayerPlacementHelper.debugSkipLayerZero = 0;
        LayerPlacementHelper.debugSkipNoLayerBlock = 0;
        LayerPlacementHelper.debugSkipNotAir = 0;
        LayerPlacementHelper.debugSkipEnclosed = 0;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                try {
                    CellData cell = cellAccessor.getCell(worldX, worldZ);
                    if (cell != null) {
                        cellsProcessed++;

                        if (LayerConfig.DEBUG_LOGGING) {
                            if (!debugLogged) {
                                debugLogged = true;
                                CRLayers.LOGGER.info("[RTF DEBUG] Snow-layer mode, worldHeight={}", worldHeight);
                            }
                            if ((localX == 0 && localZ == 0) || (localX == 15 && localZ == 0) ||
                                (localX == 0 && localZ == 15) || (localX == 15 && localZ == 15) ||
                                (localX == 8 && localZ == 8)) {
                                float scaledHeight = cell.height * worldHeight;
                                float depth = scaledHeight - (int) scaledHeight;
                                int layers = Math.round(depth * 8) - 1;
                                CRLayers.LOGGER.info("[RTF DEBUG] Cell at local {},{}: height={}, scaled={}, depth={}, layers={}",
                                    localX, localZ, cell.height, scaledHeight, depth, layers);
                            }
                        }

                        if (injectLayerAt(chunk, worldX, worldZ, worldHeight,
                            cell.height, cell.isRiver, cell.isSubmerged)) {
                            layersPlaced++;
                        }
                    } else {
                        cellsNull++;
                    }
                } catch (Exception e) {
                    // Silently skip positions that fail
                }
            }
        }

        if (LayerConfig.DEBUG_LOGGING) {
            CRLayers.LOGGER.info("[RTF] Chunk {},{}: cells={}, null={}, layers={} | skips: river={}, submerged={}, snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}",
                chunk.getPos().x, chunk.getPos().z, cellsProcessed, cellsNull, layersPlaced,
                debugSkipRiver, debugSkipSubmerged,
                LayerPlacementHelper.debugSkipSnowy, LayerPlacementHelper.debugSkipNoSurface,
                LayerPlacementHelper.debugSkipNoMapping, LayerPlacementHelper.debugSkipLayerZero,
                LayerPlacementHelper.debugSkipNoLayerBlock, LayerPlacementHelper.debugSkipNotAir,
                LayerPlacementHelper.debugSkipEnclosed);
        }
    }

    // ========== Delegated methods ==========

    public static void correctMismatchedLayers(Chunk chunk) {
        LayerPlacementHelper.correctMismatchedLayers(chunk);
    }

    public static void removeLayersAtColumns(Chunk chunk, Set<Integer> changedColumns) {
        LayerPlacementHelper.removeLayersAtColumns(chunk, changedColumns);
    }

    public static void prepareStructureBounds(Chunk chunk, net.minecraft.world.ChunkRegion region) {
        LayerPlacementHelper.prepareStructureBounds(chunk, region);
    }

    public static void clearStructureBounds() {
        LayerPlacementHelper.clearStructureBounds();
    }

    // ========== RTF Data Types ==========

    /**
     * Interface for accessing RTF Cell data.
     */
    @FunctionalInterface
    public interface CellAccessor {
        CellData getCell(int worldX, int worldZ);
    }

    /**
     * Simplified Cell data for layer calculation.
     */
    public static class CellData {
        public final float height;
        public final boolean isRiver;
        public final boolean isSubmerged;

        public CellData(float height, boolean isRiver, boolean isSubmerged) {
            this.height = height;
            this.isRiver = isRiver;
            this.isSubmerged = isSubmerged;
        }
    }
}
