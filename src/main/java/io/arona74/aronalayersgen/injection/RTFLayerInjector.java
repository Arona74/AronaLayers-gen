package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
     * - Then subtract 1 as per user request
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
    public static boolean injectLayerAt(ChunkAccess chunk, int worldX, int worldZ, int worldHeight,
                                      float height, boolean isRiver, boolean isSubmerged) {
        if (isRiver && !LayerConfig.UNDERWATER_LAYERS) {
            debugSkipRiver.incrementAndGet();
            return false;
        }

        if (isSubmerged && !LayerConfig.UNDERWATER_LAYERS) {
            debugSkipSubmerged.incrementAndGet();
            return false;
        }

        int localX = worldX & 15;
        int localZ = worldZ & 15;
        Heightmap.Types hmType = (chunk instanceof LevelChunk)
            ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.OCEAN_FLOOR_WG;
        int surfaceY = chunk.getOrCreateHeightmapUnprimed(hmType).getFirstAvailable(localX, localZ);

        boolean isSnowyBiome = false;
        if (surfaceY > Compat.minY(chunk)) {
            BlockPos biomePos = new BlockPos(worldX, surfaceY - 1, worldZ);
            var biome = chunk.getNoiseBiome(localX >> 2, surfaceY >> 2, localZ >> 2);
            isSnowyBiome = Compat.coldEnoughToSnow(biome.value(), biomePos);
        }

        boolean useSnowLayers = isSnowyBiome && LayerConfig.IMPROVE_SNOWY_BIOMES;

        int layerCount = calculateLayerCount(height, worldHeight, useSnowLayers);

        // The integer floor of RTF's scaled terrain height is the noise-derived
        // natural surface base Y — unaffected by block placement or C2ME timing.
        // Passed to LayerPlacementHelper so STRUCTURE_SKIP_ELEVATED can compare it
        // against the actual surface Y without relying on a (potentially contaminated) snapshot.
        int rtfExpectedBaseY = (int)(height * worldHeight);

        return LayerPlacementHelper.injectLayerAt(chunk, worldX, worldZ, layerCount, useSnowLayers, rtfExpectedBaseY);
    }

    // ========== RTF-specific: ChunkAccess-level Processing ==========

    private static final AtomicInteger debugSkipRiver = new AtomicInteger();
    private static final AtomicInteger debugSkipSubmerged = new AtomicInteger();
    private static boolean debugLogged = false;

    /**
     * Process an entire chunk using RTF data with snow-layer logic.
     *
     * @param chunk The chunk to process
     * @param worldHeight The world height from RTF Levels
     * @param cellAccessor Function to access Cell data for a position
     */
    public static void injectLayersForChunk(ChunkAccess chunk, int worldHeight, CellAccessor cellAccessor) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int layersPlaced = 0;
        int cellsProcessed = 0;
        int cellsNull = 0;

        debugSkipRiver.set(0);
        debugSkipSubmerged.set(0);
        LayerPlacementHelper.debugSkipSnowy.set(0);
        LayerPlacementHelper.debugSkipNoSurface.set(0);
        LayerPlacementHelper.debugSkipConservativeSurface.set(0);
        LayerPlacementHelper.debugSkipNoMapping.set(0);
        LayerPlacementHelper.debugSkipLayerZero.set(0);
        LayerPlacementHelper.debugSkipNoLayerBlock.set(0);
        LayerPlacementHelper.debugSkipNotAir.set(0);
        LayerPlacementHelper.debugSkipEnclosed.set(0);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                try {
                    CellData cell = cellAccessor.getCell(worldX, worldZ);
                    if (cell != null) {
                        cellsProcessed++;

                        if (LayerConfig.logRtf()) {
                            if (!debugLogged) {
                                debugLogged = true;
                                AronaLayersGen.LOGGER.info("[RTF DEBUG] Snow-layer mode, worldHeight={}", worldHeight);
                            }
                            if ((localX == 0 && localZ == 0) || (localX == 15 && localZ == 0) ||
                                (localX == 0 && localZ == 15) || (localX == 15 && localZ == 15) ||
                                (localX == 8 && localZ == 8)) {
                                float scaledHeight = cell.height * worldHeight;
                                float depth = scaledHeight - (int) scaledHeight;
                                int layers = Math.round(depth * 8) - 1;
                                AronaLayersGen.LOGGER.info("[RTF DEBUG] Cell at local {},{}: height={}, scaled={}, depth={}, layers={}",
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

        if (LayerConfig.logRtf()) {
            AronaLayersGen.LOGGER.info("[RTF] ChunkAccess {},{}: cells={}, null={}, layers={} | skips: river={}, submerged={}, snowy={}, noSurf={}, conserv={}, noMap={}, layerZero={}, noBlock={}, notAir={}, enclosed={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), cellsProcessed, cellsNull, layersPlaced,
                debugSkipRiver.get(), debugSkipSubmerged.get(),
                LayerPlacementHelper.debugSkipSnowy.get(), LayerPlacementHelper.debugSkipNoSurface.get(),
                LayerPlacementHelper.debugSkipConservativeSurface.get(),
                LayerPlacementHelper.debugSkipNoMapping.get(), LayerPlacementHelper.debugSkipLayerZero.get(),
                LayerPlacementHelper.debugSkipNoLayerBlock.get(), LayerPlacementHelper.debugSkipNotAir.get(),
                LayerPlacementHelper.debugSkipEnclosed.get());
        }
    }

    // ========== Delegated methods ==========

    public static void correctMismatchedLayers(ChunkAccess chunk) {
        LayerPlacementHelper.correctMismatchedLayers(chunk);
    }

    public static void removeLayersAtColumns(ChunkAccess chunk, Set<Integer> changedColumns) {
        LayerPlacementHelper.removeLayersAtColumns(chunk, changedColumns);
    }

    public static void prepareStructureBounds(ChunkAccess chunk, net.minecraft.server.level.WorldGenRegion region) {
        LayerPlacementHelper.prepareStructureBounds(chunk, region);
    }

    public static void clearStructureBounds() {
        LayerPlacementHelper.clearStructureBounds();
    }

    // ========== RTF Data Types ==========

    @FunctionalInterface
    public interface CellAccessor {
        CellData getCell(int worldX, int worldZ);
    }

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
