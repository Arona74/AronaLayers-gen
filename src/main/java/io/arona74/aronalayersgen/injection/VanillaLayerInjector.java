package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Layer injector for vanilla worldgen, driven by the sub-block surface elevation
 * recovered out of vanilla's own density field.
 *
 * <p>Vanilla decides terrain from a continuous field and keeps a block wherever that field
 * is positive, so the blocky surface is the true surface rounded up. Reading the discarded
 * fraction back gives eight times the vertical resolution the block grid can represent, and
 * spending it on layers replaces the 1-block staircase with a surface that tracks the real
 * isosurface. See {@link FractionalSurfaceSampler} for the derivation.
 *
 * <p>This is the only backend for vanilla worldgen. Two earlier ones were removed: a
 * slope/edge heuristic that read layer counts off heightmap steps, and a "noise router"
 * path that mapped a synthetic cell height through RTF's fractional formula. Neither
 * measured the terrain — the first inferred it from the staircase it was trying to hide,
 * the second from position noise that merely correlates with the landscape — so both
 * fabricated depths this one can measure.
 *
 * <p>Where the field cannot describe a column, nothing is placed and vanilla terrain
 * stands. Delegates shared placement logic to LayerPlacementHelper.
 */
public class VanillaLayerInjector {

    public static void injectLayers(ChunkAccess chunk, RandomState noiseConfig) {
        injectLayers(chunk, noiseConfig, null);
    }

    /**
     * Inject layers into a chunk during terrain generation.
     *
     * <p>Requires vanilla worldgen and a RandomState; without either there is no density
     * field to read and the chunk is left alone.
     *
     * @param chunk The chunk being generated
     * @param noiseConfig The noise configuration (may be null; resolved from RandomStateHolder if needed)
     * @param generator the chunk generator, used only to read the noise interpolation
     *                  lattice; null falls back to the vanilla overworld 4x8 cell size.
     */
    public static void injectLayers(ChunkAccess chunk, RandomState noiseConfig, net.minecraft.world.level.chunk.ChunkGenerator generator) {
        // RTF replaces the router with its own pipeline, so finalDensity no longer describes
        // the terrain that actually got placed and every fraction read from it would be
        // fiction. RTF worlds are served by RTFLayerInjector instead.
        if (RandomStateHolder.hasRTFRandomState()) {
            return;
        }

        RandomState resolved = (noiseConfig != null) ? noiseConfig : RandomStateHolder.getNoiseConfig();
        if (resolved == null) {
            AronaLayersGen.LOGGER.warn("[FractionalSurface] RandomState unavailable for chunk {},{} — leaving vanilla terrain",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
            return;
        }
        injectLayersFractional(chunk, resolved, generator);
    }

    // ========== Fractional surface path ==========

    /**
     * Inject layers from the sub-block surface elevation recovered out of vanilla's
     * density field.
     *
     * <p>Each column's layer count is the fraction of its top block that the density field
     * actually fills. Columns the field cannot describe — carved by a ravine, raised by a
     * structure, or otherwise disagreeing with the heightmap — are left bare rather than
     * given a fabricated depth.
     */
    private static void injectLayersFractional(ChunkAccess chunk, RandomState noiseConfig,
                                               net.minecraft.world.level.chunk.ChunkGenerator generator) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int layersPlaced = 0;
        int noCrossingColumns = 0;
        int mismatchColumns = 0;
        int flatGradientColumns = 0;
        int overfullColumns = 0;
        // How far the field stays solid above the world's ground on columns with no crossing.
        // Index = run length, last bucket = "at least that deep". Distinguishes a field that
        // merely disagrees by a block or two from one describing terrain a carver removed.
        int[] noCrossingRun = new int[FractionalSurfaceSampler.FIELD_RUN_PROBE + 1];

        resetDebugCounters();

        FractionalSurfaceSampler sampler = FractionalSurfaceSampler.create(noiseConfig, generator, Compat.minY(chunk));

        Heightmap.Types hmType        = (chunk instanceof LevelChunk) ? Heightmap.Types.OCEAN_FLOOR    : Heightmap.Types.OCEAN_FLOOR_WG;
        Heightmap.Types surfaceHmType = (chunk instanceof LevelChunk) ? Heightmap.Types.WORLD_SURFACE  : Heightmap.Types.WORLD_SURFACE_WG;

        int worldBottom = Compat.minY(chunk);

        int[][] floorYs = LayerPlacementHelper.computeGroundFloorYs(chunk, hmType, startX, startZ, worldBottom);

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
                    // column takes the top of the range. Left bare it sat as a pit beneath
                    // neighbours carrying six and seven layers.
                    layerCount = FractionalSurfaceSampler.fullBlockLayers();
                    overfullColumns++;
                } else {
                    // No crossing, gradient usable, field not merely overfull: the field and the
                    // placed world describe different terrain here. Nothing measurable is left, so
                    // the column keeps its vanilla surface. The run histogram records how deep the
                    // disagreement goes, which is the diagnostic for whether a carver did it.
                    noCrossingRun[sampler.fieldSolidRunAbove(worldX, worldZ, topSolidY, FractionalSurfaceSampler.FIELD_RUN_PROBE)]++;
                    layerCount = 0;
                    noCrossingColumns++;
                }

                if (LayerConfig.logVanilla() && localX == 8 && localZ == 8) {
                    String note = Float.isNaN(elevation)
                                ? (sampler.isOverfull(worldX, worldZ, topSolidY)
                                    ? " (field overfull by one block — full count)"
                                    : " (no crossing — bare)")
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
            AronaLayersGen.LOGGER.info("[FractionalSurface] ChunkAccess {},{}: layers={} noCrossing={}/256 blockMismatch={}/256 flatGradient={}/256 overfull={}/256 noCrossingRun={} | skips: snowy={}, noSurf={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}, structElev={}, conservSurf={} | markersReplaced={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), layersPlaced, noCrossingColumns, mismatchColumns, flatGradientColumns, overfullColumns, java.util.Arrays.toString(noCrossingRun),
                LayerPlacementHelper.debugSkipSnowy.get(), LayerPlacementHelper.debugSkipNoSurface.get(),
                LayerPlacementHelper.debugSkipNoMapping.get(), LayerPlacementHelper.debugSkipLayerZero.get(),
                LayerPlacementHelper.debugSkipNoLayerBlock.get(), LayerPlacementHelper.debugSkipNotAir.get(),
                LayerPlacementHelper.debugSkipEnclosed.get(),
                LayerPlacementHelper.debugSkipStructureElevated.get(),
                LayerPlacementHelper.debugSkipConservativeSurface.get(),
                LayerPlacementHelper.debugReplacedMarker.get());
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

    private static void resetDebugCounters() {
        LayerPlacementHelper.debugSkipSnowy.set(0);
        LayerPlacementHelper.debugSkipNoSurface.set(0);
        LayerPlacementHelper.debugSkipNoMapping.set(0);
        LayerPlacementHelper.debugSkipLayerZero.set(0);
        LayerPlacementHelper.debugSkipNoLayerBlock.set(0);
        LayerPlacementHelper.debugSkipNotAir.set(0);
        LayerPlacementHelper.debugSkipEnclosed.set(0);
        LayerPlacementHelper.debugReplacedMarker.set(0);
    }
}
