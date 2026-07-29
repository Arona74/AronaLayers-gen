package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibility layer for ReTerraForged integration.
 * Uses reflection to access RTF classes without hard dependency.
 */
public class RTFCompat {

    private static boolean rtfChecked = false;
    private static boolean rtfAvailable = false;

    private static Class<?> rtfRandomStateClass;
    private static Class<?> generatorContextClass;
    private static Class<?> cellClass;
    private static Class<?> tileCacheClass;
    private static Class<?> tileClass;
    private static Class<?> tileChunkClass;
    private static Class<?> terrainClass;

    private static Method generatorContextMethod;
    private static Method cacheProvideMethod;
    private static Method cachePeekMethod;
    private static boolean cachePeekReturnsOptional = false;
    private static Method getChunkReaderMethod;
    private static Method getCellMethod;
    private static Method isSubmergedMethod;
    private static Method isRiverMethod;

    private static Field heightField;
    private static Field terrainField;
    private static Field cacheField;

    private static Field generatorField;
    private static Field heightmapFieldOnGenerator;
    private static Method getHeightmapMethodOnGenerator;
    private static Method getHeightmapMethod;
    private static Field worldHeightField;

    private static int cachedWorldHeight = -1;
    private static boolean nullContextWarned = false;
    private static boolean nullCacheWarned = false;

    public static void resetWorldState() {
        cachedWorldHeight = -1;
        nullContextWarned = false;
        nullCacheWarned = false;
    }

    public static boolean isRTFAvailable() {
        if (!rtfChecked) {
            rtfChecked = true;
            rtfAvailable = checkRTF();
        }
        return rtfAvailable;
    }

    private static boolean checkRTF() {
        if (!FabricLoader.getInstance().isModLoaded("reterraforged")) {
            AronaLayersGen.LOGGER.info("ReTerraForged not detected, RTF layer injection disabled");
            return false;
        }

        AronaLayersGen.LOGGER.info("ReTerraForged detected, initializing RTF compatibility...");

        try {
            rtfRandomStateClass = Class.forName("raccoonman.reterraforged.world.worldgen.RTFRandomState");
            AronaLayersGen.LOGGER.info("Loaded RTFRandomState class");
            generatorContextClass = Class.forName("raccoonman.reterraforged.world.worldgen.GeneratorContext");
            cellClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.Cell");
            tileCacheClass = Class.forName("raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache");
            tileClass = Class.forName("raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile");
            tileChunkClass = Class.forName("raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile$Chunk");
            terrainClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain");

            generatorContextMethod = rtfRandomStateClass.getMethod("generatorContext");
            cacheField = generatorContextClass.getField("cache");

            cacheProvideMethod = tileCacheClass.getMethod("provideAtChunk", int.class, int.class);
            // Prefer a non-blocking "if present" accessor to avoid deadlocks during chunk gen.
            // Try provideAtChunkIfPresent (returns Tile directly), then peek (returns Optional<Tile>).
            try {
                cachePeekMethod = tileCacheClass.getMethod("provideAtChunkIfPresent", int.class, int.class);
                cachePeekReturnsOptional = false;
                AronaLayersGen.LOGGER.info("[RTF] Found TileCache.provideAtChunkIfPresent() - will use non-blocking tile access");
            } catch (NoSuchMethodException e1) {
                try {
                    cachePeekMethod = tileCacheClass.getMethod("peek", int.class, int.class);
                    cachePeekReturnsOptional = true;
                    AronaLayersGen.LOGGER.info("[RTF] Found TileCache.peek() - will use non-blocking tile access");
                } catch (NoSuchMethodException e2) {
                    AronaLayersGen.LOGGER.warn("[RTF] No non-blocking tile accessor found, falling back to provideAtChunk (may cause deadlocks)");
                }
            }
            getChunkReaderMethod = tileClass.getMethod("getChunkReader", int.class, int.class);
            getCellMethod = tileChunkClass.getMethod("getCell", int.class, int.class);

            heightField = cellClass.getField("height");
            terrainField = cellClass.getField("terrain");

            isSubmergedMethod = terrainClass.getMethod("isSubmerged");
            isRiverMethod = terrainClass.getMethod("isRiver");

            generatorField = generatorContextClass.getField("generator");
            Class<?> heightmapClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap");
            getHeightmapMethod = heightmapClass.getMethod("levels");
            Class<?> levelsClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels");
            worldHeightField = levelsClass.getField("worldHeight");

            Class<?> generatorType = generatorField.getType();
            if (!heightmapClass.isAssignableFrom(generatorType)) {
                for (Field f : generatorType.getFields()) {
                    if (heightmapClass.isAssignableFrom(f.getType())) {
                        heightmapFieldOnGenerator = f;
                        AronaLayersGen.LOGGER.info("Found Heightmap via {}.{}", generatorType.getSimpleName(), f.getName());
                        break;
                    }
                }
                if (heightmapFieldOnGenerator == null) {
                    // No public Heightmap field — try getHeightmap() method (used in some RTF versions)
                    try {
                        Method m = generatorType.getMethod("getHeightmap");
                        if (heightmapClass.isAssignableFrom(m.getReturnType())) {
                            getHeightmapMethodOnGenerator = m;
                            AronaLayersGen.LOGGER.info("Found Heightmap via {}.getHeightmap()", generatorType.getSimpleName());
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
                if (heightmapFieldOnGenerator == null && getHeightmapMethodOnGenerator == null) {
                    AronaLayersGen.LOGGER.warn("Could not find Heightmap on {}. worldHeight will use fallback 256.", generatorType.getName());
                }
            }

            AronaLayersGen.LOGGER.info("ReTerraForged detected, RTF layer injection available");
            return true;
        } catch (Exception e) {
            AronaLayersGen.LOGGER.warn("Failed to initialize RTF compatibility: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Inject layers using RTF terrain data.
     *
     * @param chunk The chunk to process
     * @param randomState The RandomState (may be RTFRandomState)
     * @return true if RTF injection was performed
     */
    public static boolean injectLayersWithRTF(ChunkAccess chunk, Object randomState) {
        if (!isRTFAvailable() || randomState == null) {
            if (LayerConfig.logRtf()) {
                AronaLayersGen.LOGGER.info("[RTF] Injection skipped: available={}, randomState={}", isRTFAvailable(), randomState != null);
            }
            return false;
        }

        try {
            if (!rtfRandomStateClass.isInstance(randomState)) {
                if (LayerConfig.logRtf()) {
                    AronaLayersGen.LOGGER.info("[RTF] RandomState is not RTFRandomState: {}", randomState.getClass().getName());
                }
                return false;
            }

            Object generatorContext = generatorContextMethod.invoke(randomState);
            if (generatorContext == null) {
                if (!nullContextWarned) {
                    nullContextWarned = true;
                    AronaLayersGen.LOGGER.warn("[RTF] GeneratorContext is null — RTF tile injection disabled. " +
                        "This is likely a bug in the RTF version you are using (preset lookup may have failed during world init).");
                }
                return false;
            }

            Object tileCache = cacheField.get(generatorContext);
            if (tileCache == null) {
                if (!nullCacheWarned) {
                    nullCacheWarned = true;
                    AronaLayersGen.LOGGER.warn("[RTF] TileCache is null — RTF tile injection disabled. " +
                        "GeneratorContext was created but its cache was not initialized (possible RTF version bug).");
                }
                return false;
            }

            if (cachedWorldHeight < 0) {
                cachedWorldHeight = 256;
                try {
                    Object generator = generatorField.get(generatorContext);
                    if (generator != null) {
                        Object heightmap = generator;
                        if (getHeightmapMethodOnGenerator != null) {
                            heightmap = getHeightmapMethodOnGenerator.invoke(generator);
                        } else if (heightmapFieldOnGenerator != null) {
                            heightmap = heightmapFieldOnGenerator.get(generator);
                        }
                        if (heightmap != null) {
                            Object levels = getHeightmapMethod.invoke(heightmap);
                            if (levels != null) {
                                cachedWorldHeight = worldHeightField.getInt(levels);
                                AronaLayersGen.LOGGER.info("[RTF] Resolved worldHeight from Levels: {}", cachedWorldHeight);
                            }
                        }
                    }
                } catch (Exception e) {
                    AronaLayersGen.LOGGER.warn("[RTF] Could not get worldHeight from Levels, using fallback 256: {}", e.getMessage());
                }
            }

            final int finalWorldHeight = cachedWorldHeight;

            int chunkX = Compat.chunkX(chunk.getPos());
            int chunkZ = Compat.chunkZ(chunk.getPos());

            Object tile = getTileNonBlocking(tileCache, chunkX, chunkZ);
            if (tile == null) return false;

            Object tileChunk = getChunkReaderMethod.invoke(tile, chunkX, chunkZ);
            if (tileChunk == null) return false;

            if (LayerConfig.logRtf()) {
                AronaLayersGen.LOGGER.info("[RTF] Processing chunk at {},{} with worldHeight={}", chunkX, chunkZ, finalWorldHeight);
            }

            RTFLayerInjector.injectLayersForChunk(chunk, finalWorldHeight, (worldX, worldZ) -> {
                try {
                    Object cell = getCellMethod.invoke(tileChunk, worldX, worldZ);
                    if (cell == null) return null;

                    float height = heightField.getFloat(cell);
                    Object terrain = terrainField.get(cell);

                    boolean isRiver = false;
                    boolean isSubmerged = false;

                    if (terrain != null) {
                        isRiver = (Boolean) isRiverMethod.invoke(terrain);
                        isSubmerged = (Boolean) isSubmergedMethod.invoke(terrain);
                    }

                    return new RTFLayerInjector.CellData(height, isRiver, isSubmerged);
                } catch (Exception e) {
                    return null;
                }
            });

            return true;
        } catch (Exception e) {
            AronaLayersGen.LOGGER.warn("[RTF] Layer injection failed at {},{}: {}: {}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()),
                e.getClass().getSimpleName(), e.getCause() != null ? e.getCause().toString() : e.getMessage());
            return false;
        }
    }

    /**
     * Get a Tile from the cache without blocking.
     *
     * Uses TileCache.peek() if available — it returns an Optional<Tile> that is
     * empty when the tile isn't cached yet, rather than blocking the calling thread.
     * Falls back to provideAtChunk() only when peek() wasn't resolved at init time.
     *
     * The blocking provideAtChunk() causes a deadlock when called from within chunk
     * generation because the server thread ends up waiting on itself via getChunkBlocking.
     */
    private static Object getTileNonBlocking(Object tileCache, int chunkX, int chunkZ) throws Exception {
        if (cachePeekMethod != null) {
            Object result = cachePeekMethod.invoke(tileCache, chunkX, chunkZ);
            if (cachePeekReturnsOptional) {
                // peek() returns Optional<Tile>; unwrap it
                if (result == null) return null;
                return ((java.util.Optional<?>) result).orElse(null);
            }
            // provideAtChunkIfPresent returns Tile directly (null if not cached)
            return result;
        }
        // Fallback: provideAtChunk blocks — only reached if no non-blocking accessor was found
        return cacheProvideMethod.invoke(tileCache, chunkX, chunkZ);
    }
}
