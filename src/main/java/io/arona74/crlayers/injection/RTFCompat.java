package io.arona74.crlayers.injection;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibility layer for ReTerraForged integration.
 * Uses reflection to access RTF classes without hard dependency.
 */
public class RTFCompat {

    private static boolean rtfChecked = false;
    private static boolean rtfAvailable = false;

    // Cached reflection objects
    private static Class<?> rtfRandomStateClass;
    private static Class<?> generatorContextClass;
    private static Class<?> cellClass;
    private static Class<?> tileCacheClass;
    private static Class<?> tileClass;
    private static Class<?> tileChunkClass;
    private static Class<?> terrainClass;

    private static Method generatorContextMethod;
    private static Method cacheProvideMethod;
    private static Method getChunkReaderMethod;
    private static Method getCellMethod;
    private static Method isSubmergedMethod;
    private static Method isRiverMethod;

    // Cell data fields
    private static Field heightField;
    private static Field terrainField;
    private static Field cacheField;

    // For snow-layer height scaling
    private static Field generatorField;
    private static Field heightmapFieldOnGenerator; // generator -> heightmap (intermediate step)
    private static Method getHeightmapMethod;
    private static Field worldHeightField;

    // Cache worldHeight so we only resolve it once
    private static int cachedWorldHeight = -1;

    /**
     * Check if ReTerraForged is available.
     */
    public static boolean isRTFAvailable() {
        if (!rtfChecked) {
            rtfChecked = true;
            rtfAvailable = checkRTF();
        }
        return rtfAvailable;
    }

    private static boolean checkRTF() {
        if (!FabricLoader.getInstance().isModLoaded("reterraforged")) {
            CRLayers.LOGGER.info("ReTerraForged not detected, RTF layer injection disabled");
            return false;
        }

        CRLayers.LOGGER.info("ReTerraForged detected, initializing RTF compatibility...");

        try {
            // Load RTF classes
            rtfRandomStateClass = Class.forName("raccoonman.reterraforged.world.worldgen.RTFRandomState");
            CRLayers.LOGGER.info("Loaded RTFRandomState class");
            generatorContextClass = Class.forName("raccoonman.reterraforged.world.worldgen.GeneratorContext");
            cellClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.Cell");
            tileCacheClass = Class.forName("raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache");
            tileClass = Class.forName("raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile");
            tileChunkClass = Class.forName("raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile$Chunk");
            terrainClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain");

            // Get methods
            generatorContextMethod = rtfRandomStateClass.getMethod("generatorContext");
            cacheField = generatorContextClass.getField("cache");

            // TileCache methods
            cacheProvideMethod = tileCacheClass.getMethod("provideAtChunk", int.class, int.class);

            // Tile methods
            getChunkReaderMethod = tileClass.getMethod("getChunkReader", int.class, int.class);

            // Tile.Chunk methods
            getCellMethod = tileChunkClass.getMethod("getCell", int.class, int.class);

            // Cell fields (for snow-layer approach we mainly need height)
            heightField = cellClass.getField("height");
            terrainField = cellClass.getField("terrain");

            // Terrain methods
            isSubmergedMethod = terrainClass.getMethod("isSubmerged");
            isRiverMethod = terrainClass.getMethod("isRiver");

            // Access Levels for height scaling
            // GeneratorContext.generator -> [intermediate] -> Heightmap.levels() -> Levels.worldHeight
            generatorField = generatorContextClass.getField("generator");
            Class<?> heightmapClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap");
            getHeightmapMethod = heightmapClass.getMethod("levels");
            Class<?> levelsClass = Class.forName("raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels");
            worldHeightField = levelsClass.getField("worldHeight");

            // The generator field may not be a Heightmap directly - discover the
            // intermediate field if the generator type contains a Heightmap field
            Class<?> generatorType = generatorField.getType();
            if (!heightmapClass.isAssignableFrom(generatorType)) {
                for (Field f : generatorType.getFields()) {
                    if (heightmapClass.isAssignableFrom(f.getType())) {
                        heightmapFieldOnGenerator = f;
                        CRLayers.LOGGER.info("Found Heightmap via {}.{}", generatorType.getSimpleName(), f.getName());
                        break;
                    }
                }
                if (heightmapFieldOnGenerator == null) {
                    CRLayers.LOGGER.warn("Could not find Heightmap field on {}. worldHeight will use fallback.", generatorType.getName());
                }
            }

            CRLayers.LOGGER.info("ReTerraForged detected, RTF layer injection available");
            return true;
        } catch (Exception e) {
            CRLayers.LOGGER.warn("Failed to initialize RTF compatibility: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Inject layers using RTF terrain data.
     * Uses snow-layer logic: calculates layers from fractional terrain height.
     *
     * @param chunk The chunk to process
     * @param randomState The RandomState (may be RTFRandomState)
     * @return true if RTF injection was performed, false otherwise
     */
    public static boolean injectLayersWithRTF(Chunk chunk, Object randomState) {
        if (!isRTFAvailable() || randomState == null) {
            if (LayerConfig.DEBUG_LOGGING) {
                CRLayers.LOGGER.info("[RTF] Injection skipped: available={}, randomState={}", isRTFAvailable(), randomState != null);
            }
            return false;
        }

        try {
            // Check if randomState is RTFRandomState
            if (!rtfRandomStateClass.isInstance(randomState)) {
                if (LayerConfig.DEBUG_LOGGING) {
                    CRLayers.LOGGER.info("[RTF] RandomState is not RTFRandomState: {}", randomState.getClass().getName());
                }
                return false;
            }

            // Get GeneratorContext
            Object generatorContext = generatorContextMethod.invoke(randomState);
            if (generatorContext == null) {
                if (LayerConfig.DEBUG_LOGGING) {
                    CRLayers.LOGGER.info("[RTF] GeneratorContext is null");
                }
                return false;
            }

            // Get TileCache
            Object tileCache = cacheField.get(generatorContext);
            if (tileCache == null) {
                if (LayerConfig.DEBUG_LOGGING) {
                    CRLayers.LOGGER.info("[RTF] TileCache is null");
                }
                return false;
            }

            // Get worldHeight from Levels for snow-layer calculation
            // Path: GeneratorContext.generator -> [heightmap field] -> levels() -> worldHeight
            if (cachedWorldHeight < 0) {
                cachedWorldHeight = 256; // Default fallback
                try {
                    Object generator = generatorField.get(generatorContext);
                    if (generator != null) {
                        // If generator isn't a Heightmap directly, get the Heightmap from it
                        Object heightmap = generator;
                        if (heightmapFieldOnGenerator != null) {
                            heightmap = heightmapFieldOnGenerator.get(generator);
                        }
                        if (heightmap != null) {
                            Object levels = getHeightmapMethod.invoke(heightmap);
                            if (levels != null) {
                                cachedWorldHeight = worldHeightField.getInt(levels);
                                CRLayers.LOGGER.info("[RTF] Resolved worldHeight from Levels: {}", cachedWorldHeight);
                            }
                        }
                    }
                } catch (Exception e) {
                    CRLayers.LOGGER.warn("[RTF] Could not get worldHeight from Levels, using fallback 256: {}", e.getMessage());
                }
            }

            final int finalWorldHeight = cachedWorldHeight;

            int chunkX = chunk.getPos().x;
            int chunkZ = chunk.getPos().z;

            // Get Tile for this chunk
            Object tile = cacheProvideMethod.invoke(tileCache, chunkX, chunkZ);
            if (tile == null) {
                return false;
            }

            // Get Tile.Chunk
            Object tileChunk = getChunkReaderMethod.invoke(tile, chunkX, chunkZ);
            if (tileChunk == null) {
                return false;
            }

            if (LayerConfig.DEBUG_LOGGING) {
                CRLayers.LOGGER.info("[RTF] Processing chunk at {},{} with worldHeight={}", chunkX, chunkZ, finalWorldHeight);
            }

            // Process each position in the chunk using snow-layer logic
            RTFLayerInjector.injectLayersForChunk(chunk, finalWorldHeight, (worldX, worldZ) -> {
                try {
                    Object cell = getCellMethod.invoke(tileChunk, worldX, worldZ);
                    if (cell == null) {
                        return null;
                    }

                    float height = heightField.getFloat(cell);
                    Object terrain = terrainField.get(cell);

                    // Only use terrain type for river detection (not riverMask,
                    // which has a wide gradient causing 30-40 block exclusion zones)
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
            CRLayers.LOGGER.debug("RTF layer injection failed: {}", e.getMessage());
            return false;
        }
    }
}
