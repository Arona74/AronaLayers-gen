package io.arona74.crlayers.injection;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * Vanilla layer injector using heightmap edge detection.
 * Places layers near terrain edges where height transitions occur.
 * Flat terrain gets no layers.
 *
 * Layer distribution follows natural material accumulation:
 * - Bottom of slopes: thick layers (material accumulates from above)
 * - Top of slopes: thin layers (material erodes/slides off)
 * - Flat terrain: no layers
 *
 * Delegates shared placement logic to LayerPlacementHelper.
 */
public class VanillaLayerInjector {

    /**
     * Inject layers into a chunk during terrain generation.
     *
     * @param chunk The chunk being generated
     * @param noiseConfig The noise configuration
     */
    public static void injectLayers(Chunk chunk, NoiseConfig noiseConfig) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int layersPlaced = 0;

        // Reset shared debug counters
        LayerPlacementHelper.debugSkipSnowy = 0;
        LayerPlacementHelper.debugSkipNoSurface = 0;
        LayerPlacementHelper.debugSkipNoMapping = 0;
        LayerPlacementHelper.debugSkipLayerZero = 0;
        LayerPlacementHelper.debugSkipNoLayerBlock = 0;
        LayerPlacementHelper.debugSkipNotAir = 0;
        LayerPlacementHelper.debugSkipEnclosed = 0;

        // Auto-detect heightmap type
        Heightmap.Type hmType = (chunk instanceof WorldChunk)
            ? Heightmap.Type.OCEAN_FLOOR : Heightmap.Type.OCEAN_FLOOR_WG;

        // Pre-compute ground-level heights for the entire chunk.
        // The raw heightmap (OCEAN_FLOOR) includes tree canopy, logs, and other
        // features that block motion. We scan down from each heightmap position
        // to find the actual terrain surface (a block with a layer mapping).
        // This gives us true terrain shape for accurate edge detection.
        int[][] groundHeights = computeGroundHeights(chunk, hmType);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                int layerCount = calculateLayerCount(groundHeights, localX, localZ, chunk.getBottomY());

                if (LayerPlacementHelper.injectLayerAt(chunk, worldX, worldZ, layerCount, false)) {
                    layersPlaced++;
                }
            }
        }

        if (LayerConfig.DEBUG_LOGGING) {
            CRLayers.LOGGER.info("[Vanilla] Chunk {},{}: layers={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}",
                chunk.getPos().x, chunk.getPos().z, layersPlaced,
                LayerPlacementHelper.debugSkipSnowy, LayerPlacementHelper.debugSkipNoSurface,
                LayerPlacementHelper.debugSkipNoMapping, LayerPlacementHelper.debugSkipLayerZero,
                LayerPlacementHelper.debugSkipNoLayerBlock, LayerPlacementHelper.debugSkipNotAir,
                LayerPlacementHelper.debugSkipEnclosed);
        }
    }

    /**
     * Pre-compute a 16x16 ground height array for the chunk.
     * For each column, starts at the heightmap Y and scans down to find the
     * actual terrain surface (a block with a layer mapping), ignoring trees,
     * leaves, and other non-terrain features.
     *
     * Returns the Y of the surface block + 1 (matching heightmap convention:
     * the value is the Y above the surface).
     */
    private static int[][] computeGroundHeights(Chunk chunk, Heightmap.Type hmType) {
        int[][] heights = new int[16][16];
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int bottomY = chunk.getBottomY();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int hmY = chunk.getHeightmap(hmType).get(localX, localZ);

                if (hmY <= bottomY) {
                    heights[localX][localZ] = bottomY;
                    continue;
                }

                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                // Check the block at heightmap - 1 (the surface block)
                BlockPos surfacePos = new BlockPos(worldX, hmY - 1, worldZ);
                BlockState surfaceState = chunk.getBlockState(surfacePos);

                if (LayerPlacementHelper.hasMappingFor(surfaceState.getBlock())) {
                    // Heightmap points directly at terrain surface
                    heights[localX][localZ] = hmY;
                } else {
                    // Heightmap points at tree/feature — scan down to find terrain
                    boolean found = false;
                    for (int dy = 1; dy <= 30; dy++) {
                        int checkY = hmY - 1 - dy;
                        if (checkY <= bottomY) break;

                        BlockPos checkPos = new BlockPos(worldX, checkY, worldZ);
                        BlockState checkState = chunk.getBlockState(checkPos);

                        if (LayerPlacementHelper.hasMappingFor(checkState.getBlock())) {
                            // Found terrain: height is checkY + 1 (above the surface block)
                            heights[localX][localZ] = checkY + 1;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // No mapped surface found — treat as invalid
                        heights[localX][localZ] = bottomY;
                    }
                }
            }
        }

        return heights;
    }

    /**
     * Calculate layer count for a position using edge detection on ground heights.
     * Analyzes 8 neighbors to determine height transitions.
     * Flat terrain returns 0 (no layers).
     *
     * Natural accumulation model:
     * - Bottom of slope (higher neighbors): thick layers 5-7 (material slides down here)
     * - Top of slope (lower neighbors): thin layers 1-3 (material erodes away)
     * - Mixed (both higher and lower): moderate layers based on dominant direction
     */
    private static int calculateLayerCount(int[][] groundHeights, int localX, int localZ, int bottomY) {
        int centerHeight = groundHeights[localX][localZ];

        if (centerHeight <= bottomY) {
            return 0;
        }

        EdgeInfo edgeInfo = analyzeEdge(groundHeights, localX, localZ, centerHeight, bottomY);

        if (!edgeInfo.isNearEdge) {
            return 0;
        }

        // Determine if this position is primarily at the top or bottom of a slope
        boolean hasHigherNeighbors = edgeInfo.higherNeighborCount > 0;
        boolean hasLowerNeighbors = edgeInfo.lowerNeighborCount > 0;

        if (hasHigherNeighbors && !hasLowerNeighbors) {
            // Bottom of slope only: material accumulates here
            return layersForBottom(edgeInfo.maxRiseToHigher, edgeInfo.higherNeighborCount);
        } else if (hasLowerNeighbors && !hasHigherNeighbors) {
            // Top of slope only: material erodes/slides off
            return layersForTop(edgeInfo.maxDropToLower, edgeInfo.lowerNeighborCount);
        } else {
            // Mid-slope: both higher and lower neighbors (e.g. on a hillside)
            // Blend based on which side is steeper
            int topLayers = layersForTop(edgeInfo.maxDropToLower, edgeInfo.lowerNeighborCount);
            int bottomLayers = layersForBottom(edgeInfo.maxRiseToHigher, edgeInfo.higherNeighborCount);
            return (topLayers + bottomLayers) / 2;
        }
    }

    /**
     * Layer count for the bottom of a slope (material accumulates).
     * Steeper slopes = more material slides down = thicker layers.
     */
    private static int layersForBottom(int maxRise, int higherCount) {
        if (maxRise >= 4) {
            return 7;
        } else if (maxRise >= 3) {
            return 6;
        } else if (maxRise >= 2) {
            return 5;
        } else {
            // Gentle slope (1 block rise)
            if (higherCount >= 4) {
                return 5;
            } else if (higherCount >= 2) {
                return 4;
            } else {
                return 3;
            }
        }
    }

    /**
     * Layer count for the top of a slope (material erodes).
     * Steeper drops = less material remains = thinner layers.
     */
    private static int layersForTop(int maxDrop, int lowerCount) {
        if (maxDrop >= 4) {
            return 1;
        } else if (maxDrop >= 3) {
            return 1;
        } else if (maxDrop >= 2) {
            return 2;
        } else {
            // Gentle slope (1 block drop)
            if (lowerCount >= 4) {
                return 2;
            } else if (lowerCount >= 2) {
                return 3;
            } else {
                return 3;
            }
        }
    }

    /**
     * Information about edge proximity for a position.
     */
    private static class EdgeInfo {
        boolean isNearEdge;
        int maxDropToLower;    // max height difference to lower neighbors
        int lowerNeighborCount; // count of neighbors lower than us
        int maxRiseToHigher;   // max height difference to higher neighbors
        int higherNeighborCount; // count of neighbors higher than us
    }

    /**
     * Analyze edge proximity for a position using pre-computed ground heights.
     * Checks 8 neighbors and tracks both higher and lower height differences.
     * Out-of-bounds neighbors (chunk edges) are skipped to avoid false edges.
     * Neighbors with invalid height (bottomY) are also skipped.
     */
    private static EdgeInfo analyzeEdge(int[][] groundHeights, int localX, int localZ, int centerHeight, int bottomY) {
        EdgeInfo info = new EdgeInfo();

        int[][] offsets = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
        };

        for (int[] offset : offsets) {
            int nx = localX + offset[0];
            int nz = localZ + offset[1];

            // Skip out-of-bounds neighbors
            if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) {
                continue;
            }

            int neighborHeight = groundHeights[nx][nz];

            // Skip neighbors with no valid ground surface
            if (neighborHeight <= bottomY) {
                continue;
            }

            int diff = centerHeight - neighborHeight;

            if (diff > 0) {
                // We're higher than this neighbor (top of edge)
                info.isNearEdge = true;
                info.lowerNeighborCount++;
                info.maxDropToLower = Math.max(info.maxDropToLower, diff);
            } else if (diff < 0) {
                // We're lower than this neighbor (bottom of edge)
                info.isNearEdge = true;
                info.higherNeighborCount++;
                info.maxRiseToHigher = Math.max(info.maxRiseToHigher, -diff);
            }
        }

        return info;
    }
}
