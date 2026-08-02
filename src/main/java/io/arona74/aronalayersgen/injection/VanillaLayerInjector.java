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
     * Picks the highest-fidelity path available, given a RandomState:
     * <ul>
     *   <li>fractional_surface_layer_injection — real sub-block elevation read back out
     *       of vanilla's density field. See {@link FractionalSurfaceSampler}.
     *   <li>vanilla_noise_router_layer_injection — the RTFLayerInjector fractional
     *       formula over {@link VanillaCellHeightSampler}'s synthetic height. Note that
     *       height is mostly seeded position noise, not the density functions.
     *   <li>otherwise, the slope-based heuristic.
     * </ul>
     *
     * @param chunk The chunk being generated
     * @param noiseConfig The noise configuration (may be null; resolved from RandomStateHolder if needed)
     */
    public static void injectLayers(ChunkAccess chunk, RandomState noiseConfig) {
        injectLayers(chunk, noiseConfig, null);
    }

    /**
     * @param generator the chunk generator, used only to read the noise interpolation
     *                  lattice; null falls back to the vanilla overworld 4x8 cell size.
     */
    public static void injectLayers(ChunkAccess chunk, RandomState noiseConfig, net.minecraft.world.level.chunk.ChunkGenerator generator) {
        // Both density-function paths are vanilla-only. RTF replaces the router with its
        // own pipeline, so its continents/erosion/ridges return near-constant values that
        // map to 0 layers through our formula, and finalDensity no longer describes the
        // terrain that actually got placed.
        boolean vanillaWorldgen = !RandomStateHolder.hasRTFRandomState();

        if (vanillaWorldgen
                && (LayerConfig.FRACTIONAL_SURFACE_LAYER_INJECTION || LayerConfig.VANILLA_NOISE_ROUTER_LAYER_INJECTION)) {
            RandomState resolved = (noiseConfig != null) ? noiseConfig : RandomStateHolder.getNoiseConfig();
            if (resolved != null) {
                if (LayerConfig.FRACTIONAL_SURFACE_LAYER_INJECTION) {
                    injectLayersFractional(chunk, resolved, generator);
                } else {
                    injectLayersNoiseRouter(chunk, resolved);
                }
                return;
            }
            AronaLayersGen.LOGGER.warn("[VanillaNoiseRouter] RandomState unavailable for chunk {},{} — falling back to slope injection",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
        }
        injectLayersSlope(chunk);
    }

    // ========== Fractional surface path (fractional_surface_layer_injection=true) ==========

    /**
     * Inject layers from the sub-block surface elevation recovered out of vanilla's
     * density field.
     *
     * <p>Unlike the noise-router path below, this is not a synthetic height that merely
     * correlates with terrain — it is the terrain, at eight times the vertical resolution
     * the block grid can represent. Each column's layer count is the fraction of its top
     * block that the density field actually fills, so the rendered surface follows the
     * real isosurface instead of the 1-block staircase that rounding produced. See
     * {@link FractionalSurfaceSampler} for the derivation.
     *
     * <p>Columns the field cannot describe — carved by a ravine, raised by a structure,
     * or otherwise disagreeing with the heightmap — fall back to the noise-router count
     * rather than being left bare, so coverage stays complete.
     */
    private static void injectLayersFractional(ChunkAccess chunk, RandomState noiseConfig,
                                               net.minecraft.world.level.chunk.ChunkGenerator generator) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int layersPlaced = 0;
        int fallbackColumns = 0;
        int mismatchColumns = 0;
        int flatGradientColumns = 0;
        int overfullColumns = 0;
        // How far the field stays solid above the world's ground on columns with no crossing.
        // Index = run length, last bucket = "at least that deep". Distinguishes a field that
        // merely disagrees by a block or two from one describing terrain a carver removed.
        int[] fallbackRun = new int[FractionalSurfaceSampler.FIELD_RUN_PROBE + 1];

        resetDebugCounters();

        FractionalSurfaceSampler sampler = FractionalSurfaceSampler.create(noiseConfig, generator, Compat.minY(chunk));
        VanillaCellHeightSampler fallbackSampler =
            new VanillaCellHeightSampler(noiseConfig, RandomStateHolder.getWorldSeed());

        Heightmap.Types hmType        = (chunk instanceof LevelChunk) ? Heightmap.Types.OCEAN_FLOOR    : Heightmap.Types.OCEAN_FLOOR_WG;
        Heightmap.Types surfaceHmType = (chunk instanceof LevelChunk) ? Heightmap.Types.WORLD_SURFACE  : Heightmap.Types.WORLD_SURFACE_WG;

        int worldBottom = Compat.minY(chunk);
        int worldHeight = Compat.maxY(chunk) - worldBottom;

        int[][] floorYs = computeGroundFloorYs(chunk, hmType, startX, startZ, worldBottom);

        // Confidence pass. The reconstruction is trusted only where it demonstrably matches the
        // world that was built, because on some terrain — jagged peaks measured about 250 of 256 —
        // it disagrees almost everywhere and every column would receive a fabricated depth rather
        // than a measured one. The cause is not identified: neither reproducing vanilla's marker
        // interpolation nor its quart-resolution caching moved those numbers, and vertical gradient
        // does not separate the cases (beaches sit lower than peaks). Disagreement rate does
        // separate them cleanly, so the chunk is judged on that and left alone when it fails.
        int disagreeing = 0;
        int assessed = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int probeFloor = floorYs[lx][lz];
                if (probeFloor <= worldBottom) continue;
                int probeTop = probeFloor - 1;
                float probe = sampler.surfaceElevation(startX + lx, startZ + lz, probeTop, worldBottom);
                assessed++;
                if (Float.isNaN(probe) || crossingBlockOf(probe) != probeTop) disagreeing++;
            }
        }
        double disagreementRate = assessed == 0 ? 0.0 : (double) disagreeing / assessed;
        if (disagreementRate > LayerConfig.FRACTIONAL_SURFACE_MAX_DISAGREEMENT) {
            if (LayerConfig.logVanilla()) {
                AronaLayersGen.LOGGER.info(
                    "[FractionalSurface] ChunkAccess {},{}: skipped — field disagrees with {}/{} columns ({}%), leaving vanilla terrain",
                    Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()),
                    disagreeing, assessed, Math.round(disagreementRate * 100));
            }
            return;
        }

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                int floorY   = floorYs[localX][localZ];
                int surfaceY = chunk.getOrCreateHeightmapUnprimed(surfaceHmType).getFirstAvailable(localX, localZ);
                boolean isSubmerged = surfaceY > floorY + 1;
                if (isSubmerged && !LayerConfig.UNDERWATER_LAYERS) continue;

                boolean isSnowyBiome = false;
                if (floorY > worldBottom) {
                    var biome = chunk.getNoiseBiome(localX >> 2, floorY >> 2, localZ >> 2);
                    isSnowyBiome = Compat.coldEnoughToSnow(biome.value(), new BlockPos(worldX, floorY, worldZ));
                }
                boolean useSnowLayers = isSnowyBiome && LayerConfig.IMPROVE_SNOWY_BIOMES;

                // getFirstAvailable reports the first FREE Y, so the top solid block is one below.
                int topSolidY = floorY - 1;
                float elevation = sampler.surfaceElevation(worldX, worldZ, topSolidY, worldBottom);

                int layerCount;
                if (!Float.isNaN(elevation) && crossingBlockOf(elevation) != topSolidY) {
                    // The field puts the surface in a different block than the one we are about
                    // to layer, so its fraction measures the wrong block. Taking it anyway stacks
                    // a near-full layer on ground that is already a block high, which renders as
                    // an isolated column standing proud — the visible defect. There is no usable
                    // sub-block height here, so place nothing.
                    layerCount = 0;
                    mismatchColumns++;
                } else if (!Float.isNaN(elevation)) {
                    layerCount = FractionalSurfaceSampler.layersFromFraction(elevation - topSolidY);
                } else if (sampler.isGradientUnreliable(worldX, worldZ, topSolidY)) {
                    // The field disagrees with the world here AND is too flat vertically to say
                    // where the surface is. High peaks are the case: density changes so little per
                    // block that the reconstruction's small error moves the crossing a whole block,
                    // which reported almost every column as full and buried the terrain under a
                    // uniform slab. No honest depth is recoverable, so the column is left to vanilla.
                    layerCount = 0;
                    flatGradientColumns++;
                } else if (sampler.isOverfull(worldX, worldZ, topSolidY)) {
                    // The mirror of the mismatch above: the field puts the surface a block higher
                    // than the world's ground rather than a block lower. There is no partial fill
                    // to measure because the field considers this block completely full, so the
                    // column takes the top of the range. Left to the fallback it came out bare and
                    // sat as a pit beneath neighbours carrying six and seven layers.
                    layerCount = FractionalSurfaceSampler.fullBlockLayers();
                    overfullColumns++;
                } else {
                    fallbackRun[sampler.fieldSolidRunAbove(worldX, worldZ, topSolidY, FractionalSurfaceSampler.FIELD_RUN_PROBE)]++;
                    float cellHeight = fallbackSampler.getCellHeight(worldX, worldZ, floorY, worldBottom, worldHeight);
                    // Same full-height cap as the primary route, so this mode never places a
                    // block-height layer regardless of which route produced the count.
                    layerCount = FractionalSurfaceSampler.capPlacedLayers(
                            calculateNoiseLayerCount(cellHeight, useSnowLayers));
                    fallbackColumns++;
                }

                if (LayerConfig.logVanilla() && localX == 8 && localZ == 8) {
                    String note = Float.isNaN(elevation)
                                ? (sampler.isOverfull(worldX, worldZ, topSolidY)
                                    ? " (field overfull by one block — full count)"
                                    : " (noise-router fallback)")
                            : crossingBlockOf(elevation) != topSolidY ? " (field/world block mismatch — bare)"
                            : "";
                    AronaLayersGen.LOGGER.info("[FractionalSurface DBG] center ({},{}) floorY={} topSolidY={} elevation={} layers={}{}",
                        worldX, worldZ, floorY, topSolidY,
                        Float.isNaN(elevation) ? "none" : String.format("%.4f", elevation),
                        layerCount, note);
                }

                if (LayerPlacementHelper.injectLayerAt(chunk, worldX, worldZ, layerCount, useSnowLayers)) {
                    layersPlaced++;
                }
            }
        }

        if (LayerConfig.logVanilla()) {
            AronaLayersGen.LOGGER.info("[FractionalSurface] ChunkAccess {},{}: layers={} fallbackColumns={}/256 blockMismatch={}/256 flatGradient={}/256 overfull={}/256 fallbackRun={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}, structElev={}, conservSurf={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), layersPlaced, fallbackColumns, mismatchColumns, flatGradientColumns, overfullColumns, java.util.Arrays.toString(fallbackRun),
                LayerPlacementHelper.debugSkipSnowy.get(), LayerPlacementHelper.debugSkipNoSurface.get(),
                LayerPlacementHelper.debugSkipNoMapping.get(), LayerPlacementHelper.debugSkipLayerZero.get(),
                LayerPlacementHelper.debugSkipNoLayerBlock.get(), LayerPlacementHelper.debugSkipNotAir.get(),
                LayerPlacementHelper.debugSkipEnclosed.get(),
                LayerPlacementHelper.debugSkipStructureElevated.get(),
                LayerPlacementHelper.debugSkipConservativeSurface.get());
        }
    }


    /**
     * Which block an absolute surface elevation falls inside.
     *
     * <p>A fraction is only meaningful for the block it was measured in. Where this does not
     * match the block a layer would sit on, the field and the placed world disagree about which
     * block is the surface, and the fraction describes terrain a block away from the layer.
     */
    private static int crossingBlockOf(float elevation) {
        return (int) Math.floor(elevation);
    }

    /**
     * Ground height per column, descending past anything that is not ground.
     *
     * <p>Deliberately not a bare {@code OCEAN_FLOOR} read. This injector runs twice per
     * chunk — once at {@code generateFeatures} and again at {@code LevelChunk.<init>} — and
     * on the second pass that heightmap counts the layers the first pass placed. A neighbour
     * carrying a thick layer then measures a block taller than its terrain, so a column the
     * first pass correctly left bare sees a phantom step up and gains a layer on the second;
     * its own position is still air, so the not-air guard never catches it. That is the
     * isolated-column artifact, and reading terrain rather than block tops is what prevents it.
     *
     * <p>Layer blocks have no ground mapping, which is exactly what makes them skippable here.
     */
    private static int[][] computeGroundFloorYs(ChunkAccess chunk, Heightmap.Types hmType,
                                                int startX, int startZ, int worldBottom) {
        int[][] heights = new int[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int hmY = chunk.getOrCreateHeightmapUnprimed(hmType).getFirstAvailable(lx, lz);
                if (hmY <= worldBottom) {
                    heights[lx][lz] = worldBottom;
                    continue;
                }

                int wx = startX + lx;
                int wz = startZ + lz;
                if (LayerPlacementHelper.isGroundBlock(chunk.getBlockState(new BlockPos(wx, hmY - 1, wz)))) {
                    heights[lx][lz] = hmY;
                    continue;
                }

                int found = worldBottom;
                for (int dy = 1; dy <= 30; dy++) {
                    int cy = hmY - 1 - dy;
                    if (cy <= worldBottom) break;
                    if (LayerPlacementHelper.isGroundBlock(chunk.getBlockState(new BlockPos(wx, cy, wz)))) {
                        found = cy + 1;
                        break;
                    }
                }
                heights[lx][lz] = found;
            }
        }
        return heights;
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
            AronaLayersGen.LOGGER.info("[VanillaNoiseRouter] ChunkAccess {},{}: layers={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}, structElev={}, conservSurf={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), layersPlaced,
                LayerPlacementHelper.debugSkipSnowy.get(), LayerPlacementHelper.debugSkipNoSurface.get(),
                LayerPlacementHelper.debugSkipNoMapping.get(), LayerPlacementHelper.debugSkipLayerZero.get(),
                LayerPlacementHelper.debugSkipNoLayerBlock.get(), LayerPlacementHelper.debugSkipNotAir.get(),
                LayerPlacementHelper.debugSkipEnclosed.get(),
                LayerPlacementHelper.debugSkipStructureElevated.get(),
                LayerPlacementHelper.debugSkipConservativeSurface.get());
        }
    }

    /**
     * Maps a synthetic cell height [0..1] to a layer count using the same fractional
     * formula as RTFLayerInjector, with GRADIENT_SCALE tuned for vanilla density functions.
     *
     * GRADIENT_SCALE=8 → 8 complete layer-count cycles across the [0..1] height range.
     * skipReduction=true keeps the unreduced value (used for snowy biomes like RTF does).
     *
     * <p>Public so /algdebug can mirror this path exactly instead of keeping its own copy —
     * a divergent copy is what made the command report false 'M' markers before.
     */
    private static final float GRADIENT_SCALE = 8.0f;

    public static int calculateNoiseLayerCount(float cellHeight, boolean skipReduction) {
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
            AronaLayersGen.LOGGER.info("[Vanilla] ChunkAccess {},{}: layers={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}, structElev={}, conservSurf={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), layersPlaced,
                LayerPlacementHelper.debugSkipSnowy.get(), LayerPlacementHelper.debugSkipNoSurface.get(),
                LayerPlacementHelper.debugSkipNoMapping.get(), LayerPlacementHelper.debugSkipLayerZero.get(),
                LayerPlacementHelper.debugSkipNoLayerBlock.get(), LayerPlacementHelper.debugSkipNotAir.get(),
                LayerPlacementHelper.debugSkipEnclosed.get(),
                LayerPlacementHelper.debugSkipStructureElevated.get(),
                LayerPlacementHelper.debugSkipConservativeSurface.get());
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
