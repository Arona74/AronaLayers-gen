package io.arona74.crlayers.injection;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores pre-structure heightmap snapshots for the structure_no_layers feature.
 *
 * Thread-safe: Uses ConcurrentHashMap since capture (carvers phase) and
 * comparison (WorldChunk creation) may happen on different threads under C2ME.
 *
 * Lifecycle:
 * - captureHeightmap() called from ChunkGeneratorMixin.onCarveComplete()
 * - getChangedColumns() called from NoiseBasedChunkGeneratorMixin.onChunkCreate()
 *   (atomically removes the snapshot after comparison)
 */
public class PreStructureHeightmapStorage {

    // Keyed by ChunkPos.toLong(), value is int[256] for 16x16 heightmap values
    // index = localZ * 16 + localX
    private static final ConcurrentHashMap<Long, int[]> heightmapSnapshots = new ConcurrentHashMap<>();

    /**
     * Capture the current heightmap for a chunk.
     * Call after carvers but before features/structures.
     * Uses OCEAN_FLOOR_WG since the chunk is still a ProtoChunk at this stage.
     */
    public static void captureHeightmap(Chunk chunk) {
        int[] snapshot = new int[256];
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                snapshot[localZ * 16 + localX] = chunk.getHeightmap(
                    Heightmap.Type.OCEAN_FLOOR_WG).get(localX, localZ);
            }
        }
        long key = chunk.getPos().toLong();
        heightmapSnapshots.put(key, snapshot);

        if (LayerConfig.DEBUG_LOGGING) {
            CRLayers.LOGGER.info("[StructureNoLayers] Captured heightmap for chunk {},{}",
                chunk.getPos().x, chunk.getPos().z);
        }
    }

    /**
     * Compare captured (pre-structure) heightmap with current (post-structure) heightmap.
     * Returns a Set of local coordinate indices (localZ * 16 + localX) where the
     * heightmap changed, meaning structures modified blocks in those columns.
     *
     * Uses OCEAN_FLOOR since the chunk is now a WorldChunk.
     * Returns null if no snapshot was captured for this chunk.
     *
     * Automatically removes the snapshot after comparison (cleanup).
     */
    public static Set<Integer> getChangedColumns(Chunk chunk) {
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
                int postHeight = chunk.getHeightmap(
                    Heightmap.Type.OCEAN_FLOOR).get(localX, localZ);

                // Use minimum difference of 2 to filter false positives from
                // OCEAN_FLOOR_WG vs OCEAN_FLOOR type differences near water level
                // and vegetation features. Structures change heightmaps by 3+ blocks.
                if (Math.abs(preHeight - postHeight) >= 2) {
                    changed.add(index);
                }
            }
        }

        if (LayerConfig.DEBUG_LOGGING && !changed.isEmpty()) {
            CRLayers.LOGGER.info("[StructureNoLayers] Chunk {},{}: {} columns changed by structures",
                chunk.getPos().x, chunk.getPos().z, changed.size());
        }

        return changed;
    }
}
