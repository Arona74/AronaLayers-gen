package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;

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
     * When vanilla_noise_router_layer_injection is enabled and a RandomState is
     * available, uses the same fractional height formula as RTFLayerInjector but
     * with a synthetic cell height derived from vanilla's continents, erosion, and
     * ridges density functions. Falls back to the slope-based heuristic otherwise.
     *
     * @param chunk The chunk being generated
     * @param noiseConfig The noise configuration (may be null; resolved from RandomStateHolder if needed)
     */
    public static void injectLayers(ChunkAccess chunk, RandomState noiseConfig) {
        // Only use the noise router when RTF is not the active worldgen.
        // RTF replaces vanilla's density functions with its own pipeline,
        // so its continents/erosion/ridges return near-constant values that
        // map to 0 layers through our formula.
        if (LayerConfig.VANILLA_NOISE_ROUTER_LAYER_INJECTION && !RandomStateHolder.hasRTFRandomState()) {
            RandomState resolved = (noiseConfig != null) ? noiseConfig : RandomStateHolder.getNoiseConfig();
            if (resolved != null) {
                injectLayersNoiseRouter(chunk, resolved);
                return;
            }
            AronaLayersGen.LOGGER.warn("[VanillaNoiseRouter] RandomState unavailable for chunk {},{} — falling back to slope injection",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
        }
        injectLayersSlope(chunk);
    }

    // ========== Noise Router path (vanilla_noise_router_layer_injection=true) ==========

    /**
     * Inject layers using vanilla's NoiseRouter density functions as a cell-height proxy.
     *
     * Applies the same fractional formula as RTFLayerInjector:
     *   scaled  = cellHeight * GRADIENT_SCALE
     *   depth   = frac(scaled)              — [0..1)
     *   layers  = round(depth * 8) − 1      — typically 0–7, clamped to 0–8
     *
     * GRADIENT_SCALE (8.0) controls how many layer-count cycles span the full [0..1]
     * synthetic height range. With vanilla density functions changing ~0.001–0.005
     * per block, this creates smooth transitions every ~15–50 blocks.
     */
    private static void injectLayersNoiseRouter(ChunkAccess chunk, RandomState noiseConfig) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int layersPlaced = 0;

        resetDebugCounters();

        VanillaCellHeightSampler sampler = new VanillaCellHeightSampler(noiseConfig, RandomStateHolder.getWorldSeed());

        Heightmap.Types hmType        = (chunk instanceof LevelChunk) ? Heightmap.Types.OCEAN_FLOOR    : Heightmap.Types.OCEAN_FLOOR_WG;
        Heightmap.Types surfaceHmType = (chunk instanceof LevelChunk) ? Heightmap.Types.WORLD_SURFACE  : Heightmap.Types.WORLD_SURFACE_WG;

        int worldBottom  = Compat.minY(chunk);
        int worldHeight  = Compat.maxY(chunk) - worldBottom;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                int floorY   = chunk.getOrCreateHeightmapUnprimed(hmType).getFirstAvailable(localX, localZ);
                int surfaceY = chunk.getOrCreateHeightmapUnprimed(surfaceHmType).getFirstAvailable(localX, localZ);
                boolean isSubmerged = surfaceY > floorY + 1;
                if (isSubmerged && !LayerConfig.UNDERWATER_LAYERS) continue;

                float cellHeight = sampler.getCellHeight(worldX, worldZ, floorY, worldBottom, worldHeight);
                boolean isSnowyBiome = false;
                if (floorY > worldBottom) {
                    var biome = chunk.getNoiseBiome(localX >> 2, floorY >> 2, localZ >> 2);
                    isSnowyBiome = Compat.coldEnoughToSnow(biome.value(), new BlockPos(worldX, floorY, worldZ));
                }
                boolean useSnowLayers = isSnowyBiome && LayerConfig.IMPROVE_SNOWY_BIOMES;

                int layerCount = calculateNoiseLayerCount(cellHeight, useSnowLayers);

                if (LayerConfig.logVanilla() && localX == 8 && localZ == 8) {
                    float surfNorm = (float)(floorY - worldBottom) / worldHeight;
                    float noise    = sampler.getPositionNoise(worldX, worldZ);
                    float scaled   = cellHeight * GRADIENT_SCALE;
                    AronaLayersGen.LOGGER.info("[VanillaNoiseRouter DBG] center ({},{}) floorY={} surfNorm={} noise={} cellHeight={} depth={} layers={}",
                        worldX, worldZ, floorY,
                        String.format("%.3f", surfNorm),
                        String.format("%.3f", noise),
                        String.format("%.3f", cellHeight),
                        String.format("%.3f", scaled - (int) scaled),
                        layerCount);
                }

                if (LayerPlacementHelper.injectLayerAt(chunk, worldX, worldZ, layerCount, useSnowLayers)) {
                    layersPlaced++;
                }
            }
        }

        if (LayerConfig.logVanilla()) {
            AronaLayersGen.LOGGER.info("[VanillaNoiseRouter] ChunkAccess {},{}: layers={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), layersPlaced,
                LayerPlacementHelper.debugSkipSnowy.get(), LayerPlacementHelper.debugSkipNoSurface.get(),
                LayerPlacementHelper.debugSkipNoMapping.get(), LayerPlacementHelper.debugSkipLayerZero.get(),
                LayerPlacementHelper.debugSkipNoLayerBlock.get(), LayerPlacementHelper.debugSkipNotAir.get(),
                LayerPlacementHelper.debugSkipEnclosed.get());
        }
    }

    /**
     * Maps a synthetic cell height [0..1] to a layer count using the same fractional
     * formula as RTFLayerInjector, with GRADIENT_SCALE tuned for vanilla density functions.
     *
     * GRADIENT_SCALE=8 → 8 complete layer-count cycles across the [0..1] height range.
     * skipReduction=true keeps the unreduced value (used for snowy biomes like RTF does).
     */
    private static final float GRADIENT_SCALE = 8.0f;

    private static int calculateNoiseLayerCount(float cellHeight, boolean skipReduction) {
        float scaled = cellHeight * GRADIENT_SCALE;
        float depth  = scaled - (int) scaled;
        int layers   = Math.round(depth * 8);
        if (!skipReduction) {
            layers = layers - 1;
        }
        if (layers < 1) return 0;
        return Math.min(8, layers);
    }

    // ========== Slope path (vanilla_noise_router_layer_injection=false) ==========

    /**
     * Legacy slope-based injection. Places layers near terrain height transitions:
     * bottom of slopes get thick layers, tops get thin, flat terrain gets none.
     */
    private static void injectLayersSlope(ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int layersPlaced = 0;

        resetDebugCounters();

        Heightmap.Types hmType = (chunk instanceof LevelChunk)
            ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.OCEAN_FLOOR_WG;

        int[][] groundHeights = computeGroundHeights(chunk, hmType);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                int layerCount = calculateLayerCount(groundHeights, localX, localZ, Compat.minY(chunk));

                if (LayerPlacementHelper.injectLayerAt(chunk, worldX, worldZ, layerCount, false)) {
                    layersPlaced++;
                }
            }
        }

        if (LayerConfig.logVanilla()) {
            AronaLayersGen.LOGGER.info("[Vanilla] ChunkAccess {},{}: layers={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), layersPlaced,
                LayerPlacementHelper.debugSkipSnowy.get(), LayerPlacementHelper.debugSkipNoSurface.get(),
                LayerPlacementHelper.debugSkipNoMapping.get(), LayerPlacementHelper.debugSkipLayerZero.get(),
                LayerPlacementHelper.debugSkipNoLayerBlock.get(), LayerPlacementHelper.debugSkipNotAir.get(),
                LayerPlacementHelper.debugSkipEnclosed.get());
        }
    }

    private static void resetDebugCounters() {
        LayerPlacementHelper.debugSkipSnowy.set(0);
        LayerPlacementHelper.debugSkipNoSurface.set(0);
        LayerPlacementHelper.debugSkipNoMapping.set(0);
        LayerPlacementHelper.debugSkipLayerZero.set(0);
        LayerPlacementHelper.debugSkipNoLayerBlock.set(0);
        LayerPlacementHelper.debugSkipNotAir.set(0);
        LayerPlacementHelper.debugSkipEnclosed.set(0);
    }

    private static int[][] computeGroundHeights(ChunkAccess chunk, Heightmap.Types hmType) {
        int[][] heights = new int[16][16];
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int bottomY = Compat.minY(chunk);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int hmY = chunk.getOrCreateHeightmapUnprimed(hmType).getFirstAvailable(localX, localZ);

                if (hmY <= bottomY) {
                    heights[localX][localZ] = bottomY;
                    continue;
                }

                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                // powder_snow is non-opaque so OCEAN_FLOOR doesn't count it.
                // Elevate hmY through the full contiguous powder_snow stack so the
                // ground height points above the topmost powder_snow, not inside the pile.
                Block blockAtFloor = chunk.getBlockState(new BlockPos(worldX, hmY, worldZ)).getBlock();
                if (blockAtFloor == Blocks.POWDER_SNOW && LayerPlacementHelper.hasMappingFor(blockAtFloor)) {
                    hmY++;
                    while (chunk.getBlockState(new BlockPos(worldX, hmY, worldZ)).getBlock() == Blocks.POWDER_SNOW) {
                        hmY++;
                    }
                }

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
