package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores heightmap snapshots for structure-aware layer injection.
 *
 * Two separate snapshot systems with different lifecycles:
 *
 * SURFACE snapshots (for STRUCTURE_SKIP_ELEVATED):
 *   - Captured after buildSurface(), before carvers.
 *   - Guaranteed uncontaminated even under C2ME parallel worldgen, because
 *     Minecraft's dependency chain requires neighboring chunks to reach CARVERS
 *     status before any chunk's FEATURES phase can run. So at SURFACE time,
 *     no neighboring village FEATURES (beard fill) can have modified this chunk.
 *   - Used in LayerPlacementHelper.injectLayerAt to detect terrain raised by
 *     structures (skips or replaces layer when terrain was elevated).
 *   - Lifecycle: captureSurfaceHeightmap() → peekSurfaceY() → discardSurfaceSnapshot()
 *
 * CARVERS snapshots (for STRUCTURE_NO_LAYERS):
 *   - Captured after carve(), before features.
 *   - May be contaminated under C2ME if a neighboring chunk's FEATURES runs
 *     concurrently, but still useful for detecting structure block placement
 *     (structures typically raise heightmap by more than carver contamination).
 *   - Lifecycle: captureHeightmap() → getChangedColumns() (auto-removes on read)
 *
 * Both maps are thread-safe (ConcurrentHashMap) because chunk phases may run
 * on different threads under C2ME.
 */
public class PreStructureHeightmapStorage {

    // ========== SURFACE-phase snapshots (for STRUCTURE_SKIP_ELEVATED) ==========

    private static final ConcurrentHashMap<Long, int[]> surfaceSnapshots = new ConcurrentHashMap<>();

    /**
     * Capture the OCEAN_FLOOR_WG heightmap immediately after buildSurface().
     * This is before carvers and before any C2ME-parallel village features can
     * contaminate the chunk, giving a clean pre-structure baseline.
     */
    public static void captureSurfaceHeightmap(ChunkAccess chunk) {
        int[] snapshot = new int[256];
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                snapshot[localZ * 16 + localX] = chunk.getOrCreateHeightmapUnprimed(
                    Heightmap.Types.OCEAN_FLOOR_WG).getFirstAvailable(localX, localZ);
            }
        }
        long key = chunk.getPos().toLong();
        surfaceSnapshots.put(key, snapshot);

        if (LayerConfig.logSkipElevated()) {
            AronaLayersGen.LOGGER.info("[SkipElevated] Captured surface heightmap for chunk {},{}",
                chunk.getPos().x, chunk.getPos().z);
        }
    }

    /**
     * Return the surface-phase heightmap value for a single column WITHOUT removing the snapshot.
     * The value is exclusive (top solid block is at result - 1), matching OCEAN_FLOOR_WG semantics.
     * Returns Integer.MIN_VALUE if no snapshot was captured for this chunk.
     *
     * Safe to call concurrently; does not modify the map.
     */
    public static int peekSurfaceY(ChunkAccess chunk, int localX, int localZ) {
        long key = chunk.getPos().toLong();
        int[] snapshot = surfaceSnapshots.get(key);
        if (snapshot == null) return Integer.MIN_VALUE;
        return snapshot[localZ * 16 + localX];
    }

    /**
     * Discard the surface-phase snapshot for a chunk.
     * Call after layer injection is complete for this chunk.
     */
    public static void discardSurfaceSnapshot(ChunkAccess chunk) {
        surfaceSnapshots.remove(chunk.getPos().toLong());
    }

    // ========== CARVERS-phase snapshots (for STRUCTURE_NO_LAYERS) ==========

    private static final ConcurrentHashMap<Long, int[]> heightmapSnapshots = new ConcurrentHashMap<>();

    /**
     * Capture the current OCEAN_FLOOR_WG heightmap after carvers.
     * Call after carvers but before features/structures.
     * May be contaminated under C2ME for cross-chunk structures.
     */
    public static void captureHeightmap(ChunkAccess chunk) {
        int[] snapshot = new int[256];
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                snapshot[localZ * 16 + localX] = chunk.getOrCreateHeightmapUnprimed(
                    Heightmap.Types.OCEAN_FLOOR_WG).getFirstAvailable(localX, localZ);
            }
        }
        long key = chunk.getPos().toLong();
        heightmapSnapshots.put(key, snapshot);

        if (LayerConfig.logStructureNoLayers()) {
            AronaLayersGen.LOGGER.info("[StructureNoLayers] Captured heightmap for chunk {},{}",
                chunk.getPos().x, chunk.getPos().z);
        }
    }

    /**
     * @deprecated Use peekSurfaceY for STRUCTURE_SKIP_ELEVATED.
     * Left for compatibility with any external callers; reads from the carvers snapshot.
     */
    @Deprecated
    public static int peekPreStructureSurfaceY(ChunkAccess chunk, int localX, int localZ) {
        long key = chunk.getPos().toLong();
        int[] snapshot = heightmapSnapshots.get(key);
        if (snapshot == null) return Integer.MIN_VALUE;
        return snapshot[localZ * 16 + localX];
    }

    /**
     * Discard the carvers-phase snapshot without comparison.
     * Only needed if getChangedColumns will not be called.
     */
    public static void discardSnapshot(ChunkAccess chunk) {
        heightmapSnapshots.remove(chunk.getPos().toLong());
    }

    /**
     * Compare captured (pre-structure) heightmap with current (post-structure) heightmap.
     * Returns a Set of local coordinate indices (localZ * 16 + localX) where the
     * heightmap changed. Returns null if no snapshot was captured for this chunk.
     *
     * Automatically removes the snapshot after comparison.
     */
    public static Set<Integer> getChangedColumns(ChunkAccess chunk) {
        long key = chunk.getPos().toLong();
        int[] snapshot = heightmapSnapshots.remove(key);

        if (snapshot == null) {
            return null;
        }

        Set<Integer> changed = new HashSet<>();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int index = localZ * 16 + localX;
                int preHeight = snapshot[index];
                int postHeight = chunk.getOrCreateHeightmapUnprimed(
                    Heightmap.Types.OCEAN_FLOOR).getFirstAvailable(localX, localZ);

                if (postHeight - preHeight >= 2) {
                    changed.add(index);
                }
            }
        }

        if (LayerConfig.logStructureNoLayers() && !changed.isEmpty()) {
            AronaLayersGen.LOGGER.info("[StructureNoLayers] ChunkAccess {},{}: {} columns changed by structures",
                chunk.getPos().x, chunk.getPos().z, changed.size());
        }

        return changed;
    }
}
