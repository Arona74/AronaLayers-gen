package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
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
     * @param noiseConfig The noise configuration (may be null in POST_FEATURES context)
     */
    public static void injectLayers(Chunk chunk, NoiseConfig noiseConfig) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int layersPlaced = 0;

        LayerPlacementHelper.debugSkipSnowy = 0;
        LayerPlacementHelper.debugSkipNoSurface = 0;
        LayerPlacementHelper.debugSkipNoMapping = 0;
        LayerPlacementHelper.debugSkipLayerZero = 0;
        LayerPlacementHelper.debugSkipNoLayerBlock = 0;
        LayerPlacementHelper.debugSkipNotAir = 0;
        LayerPlacementHelper.debugSkipEnclosed = 0;

        Heightmap.Type hmType = (chunk instanceof WorldChunk)
            ? Heightmap.Type.OCEAN_FLOOR : Heightmap.Type.OCEAN_FLOOR_WG;

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
            AronaLayersGen.LOGGER.info("[Vanilla] Chunk {},{}: layers={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}",
                chunk.getPos().x, chunk.getPos().z, layersPlaced,
                LayerPlacementHelper.debugSkipSnowy, LayerPlacementHelper.debugSkipNoSurface,
                LayerPlacementHelper.debugSkipNoMapping, LayerPlacementHelper.debugSkipLayerZero,
                LayerPlacementHelper.debugSkipNoLayerBlock, LayerPlacementHelper.debugSkipNotAir,
                LayerPlacementHelper.debugSkipEnclosed);
        }
    }

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

                BlockPos surfacePos = new BlockPos(worldX, hmY - 1, worldZ);
                BlockState surfaceState = chunk.getBlockState(surfacePos);

                if (LayerPlacementHelper.hasMappingFor(surfaceState.getBlock())) {
                    heights[localX][localZ] = hmY;
                } else {
                    boolean found = false;
                    for (int dy = 1; dy <= 30; dy++) {
                        int checkY = hmY - 1 - dy;
                        if (checkY <= bottomY) break;

                        BlockPos checkPos = new BlockPos(worldX, checkY, worldZ);
                        BlockState checkState = chunk.getBlockState(checkPos);

                        if (LayerPlacementHelper.hasMappingFor(checkState.getBlock())) {
                            heights[localX][localZ] = checkY + 1;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        heights[localX][localZ] = bottomY;
                    }
                }
            }
        }

        return heights;
    }

    private static int calculateLayerCount(int[][] groundHeights, int localX, int localZ, int bottomY) {
        int centerHeight = groundHeights[localX][localZ];

        if (centerHeight <= bottomY) {
            return 0;
        }

        EdgeInfo edgeInfo = analyzeEdge(groundHeights, localX, localZ, centerHeight, bottomY);

        if (!edgeInfo.isNearEdge) {
            return 0;
        }

        boolean hasHigherNeighbors = edgeInfo.higherNeighborCount > 0;
        boolean hasLowerNeighbors = edgeInfo.lowerNeighborCount > 0;

        if (hasHigherNeighbors && !hasLowerNeighbors) {
            return layersForBottom(edgeInfo.maxRiseToHigher, edgeInfo.higherNeighborCount);
        } else if (hasLowerNeighbors && !hasHigherNeighbors) {
            return layersForTop(edgeInfo.maxDropToLower, edgeInfo.lowerNeighborCount);
        } else {
            int topLayers = layersForTop(edgeInfo.maxDropToLower, edgeInfo.lowerNeighborCount);
            int bottomLayers = layersForBottom(edgeInfo.maxRiseToHigher, edgeInfo.higherNeighborCount);
            return (topLayers + bottomLayers) / 2;
        }
    }

    private static int layersForBottom(int maxRise, int higherCount) {
        if (maxRise >= 4) {
            return 7;
        } else if (maxRise >= 3) {
            return 6;
        } else if (maxRise >= 2) {
            return 5;
        } else {
            if (higherCount >= 4) return 5;
            else if (higherCount >= 2) return 4;
            else return 3;
        }
    }

    private static int layersForTop(int maxDrop, int lowerCount) {
        if (maxDrop >= 4) {
            return 1;
        } else if (maxDrop >= 3) {
            return 1;
        } else if (maxDrop >= 2) {
            return 2;
        } else {
            if (lowerCount >= 4) return 2;
            else if (lowerCount >= 2) return 3;
            else return 3;
        }
    }

    private static class EdgeInfo {
        boolean isNearEdge;
        int maxDropToLower;
        int lowerNeighborCount;
        int maxRiseToHigher;
        int higherNeighborCount;
    }

    private static EdgeInfo analyzeEdge(int[][] groundHeights, int localX, int localZ, int centerHeight, int bottomY) {
        EdgeInfo info = new EdgeInfo();

        int[][] offsets = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
        };

        for (int[] offset : offsets) {
            int nx = localX + offset[0];
            int nz = localZ + offset[1];

            if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) continue;

            int neighborHeight = groundHeights[nx][nz];
            if (neighborHeight <= bottomY) continue;

            int diff = centerHeight - neighborHeight;

            if (diff > 0) {
                info.isNearEdge = true;
                info.lowerNeighborCount++;
                info.maxDropToLower = Math.max(info.maxDropToLower, diff);
            } else if (diff < 0) {
                info.isNearEdge = true;
                info.higherNeighborCount++;
                info.maxRiseToHigher = Math.max(info.maxRiseToHigher, -diff);
            }
        }

        return info;
    }
}
