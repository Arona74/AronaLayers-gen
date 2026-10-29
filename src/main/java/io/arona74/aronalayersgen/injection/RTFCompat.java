package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibility layer for ReTerraForged integration.
 * Uses reflection to access RTF classes without hard dependency.
 */
public class RTFCompat {

    private static boolean rtfChecked = false;
    private static boolean rtfAvailable = false;

    /**
     * The TerraForged lineage forks we can drive. FreeTerraForged (ETcodehome's community fork) is a
     * rename of ReTerraForged: same class layout under a new package root, a new mod id, and the
     * RandomState duck interface renamed RTFRandomState -> FTFRandomState. The two declare each other
     * incompatible, so at most one is ever present; the first loaded one wins.
     */
    public record Flavor(String modId, String displayName, String packageRoot, String randomStateIface) {
        String cls(String relative) { return packageRoot + "." + relative; }
    }

    public static final Flavor FREE_TERRAFORGED =
        new Flavor("freeterraforged", "FreeTerraForged", "etcodehome.freeterraforged", "FTFRandomState");
    public static final Flavor RE_TERRAFORGED =
        new Flavor("reterraforged", "ReTerraForged", "raccoonman.reterraforged", "RTFRandomState");
    private static final Flavor[] FLAVORS = { FREE_TERRAFORGED, RE_TERRAFORGED };

    private static volatile Flavor detectedFlavor;
    private static volatile boolean flavorChecked = false;

    /** The installed TerraForged fork, or null if none is loaded. */
    public static Flavor flavor() {
        if (!flavorChecked) {
            for (Flavor f : FLAVORS) {
                if (FabricLoader.getInstance().isModLoaded(f.modId())) { detectedFlavor = f; break; }
            }
            flavorChecked = true;
        }
        return detectedFlavor;
    }

    /** True if any supported TerraForged fork is loaded (used for mixin gating). */
    public static boolean isAnyTerraForgedLoaded() {
        for (Flavor f : FLAVORS) {
            if (FabricLoader.getInstance().isModLoaded(f.modId())) return true;
        }
        return false;
    }

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
    // FreeTerraForged dropped the chunk-coordinate non-blocking accessors; its only non-blocking
    // lookup is provideIfPresent(tileX, tileZ), so we convert chunk -> tile via chunkToTile.
    private static boolean cachePeekTakesTileCoords = false;
    private static Method chunkToTileMethod;
    // FreeTerraForged (2026-10+): tiles are reference-counted. acquireIfPresent(tileX, tileZ) returns a
    // Lease that must stay open while cells are read and be closed afterwards, or the tile can be
    // recycled under us (and leaked leases pin the cache).
    private static boolean leaseMode = false;
    private static Method leaseTileMethod;
    private static Method leaseCloseMethod;
    private static Method leaseAcquireBlockingMethod; // TileCache.acquire(tileX, tileZ), regenerates on miss
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

    // FreeTerraForged after "deleteTiles" (PR #325, 2026-10-24): no Tile/TileCache any more. Each
    // chunk carries its own 256 filtered cells (IChunkCellsHolder, mixed into ChunkAccess), rebuilt
    // from GeneratorContext.filteredChunkCache when missing, and the context exposes Levels directly.
    private static boolean chunkCellsMode = false;
    private static Class<?> chunkCellsHolderClass;
    private static Method getChunkCellsMethod;          // <modid>$getChunkCells(), null when not built yet
    private static Method getOrCreateChunkCellsMethod;  // <modid>$getOrCreateChunkCells(GeneratorContext), may build the region
    private static Field filteredChunkCacheField;
    private static Method getExistingFilteredCellMethod; // FilteredChunkCache.getExistingFilteredCell(blockX, blockZ), non-blocking
    private static Field levelsField;

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

    private static volatile Method isTerraForgedMethod;
    private static volatile boolean isTerraForgedBound;

    /**
     * Whether {@code randomState} belongs to a dimension the TerraForged fork generates. Every
     * RandomState implements the fork's duck interface once the mod is loaded, so the interface
     * alone says nothing. FreeTerraForged answers directly through {@code isTerraForged()}, which
     * is set from the dimension's generator before the GeneratorContext necessarily exists;
     * ReTerraForged has no such flag, but only builds a GeneratorContext for its own dimensions.
     */
    public static boolean isTerraForgedDimension(Object randomState) {
        if (!isRTFAvailable() || randomState == null || !rtfRandomStateClass.isInstance(randomState)) return false;
        try {
            if (!isTerraForgedBound) {
                try {
                    isTerraForgedMethod = rtfRandomStateClass.getMethod("isTerraForged");
                } catch (NoSuchMethodException e) {
                    isTerraForgedMethod = null;
                }
                isTerraForgedBound = true;
            }
            if (isTerraForgedMethod != null) return (Boolean) isTerraForgedMethod.invoke(randomState);
            return generatorContextMethod.invoke(randomState) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkRTF() {
        Flavor fl = flavor();
        if (fl == null) {
            AronaLayersGen.LOGGER.info("No TerraForged fork (FreeTerraForged / ReTerraForged) detected, RTF layer injection disabled");
            return false;
        }

        AronaLayersGen.LOGGER.info("{} detected, initializing TerraForged compatibility...", fl.displayName());

        try {
            rtfRandomStateClass = Class.forName(fl.cls("world.worldgen." + fl.randomStateIface()));
            AronaLayersGen.LOGGER.info("Loaded {} class", fl.randomStateIface());
            generatorContextClass = Class.forName(fl.cls("world.worldgen.GeneratorContext"));
            cellClass = Class.forName(fl.cls("world.worldgen.cell.Cell"));
            terrainClass = Class.forName(fl.cls("world.worldgen.cell.terrain.Terrain"));
            generatorContextMethod = rtfRandomStateClass.getMethod("generatorContext");
            heightField = cellClass.getField("height");
            terrainField = cellClass.getField("terrain");
            isSubmergedMethod = terrainClass.getMethod("isSubmerged");
            isRiverMethod = terrainClass.getMethod("isRiver");

            if (!classExists(fl.cls("world.worldgen.densityfunction.tile.TileCache"))) {
                bindChunkCells(fl);
                chunkCellsMode = true;
                AronaLayersGen.LOGGER.info("{} bound (per-chunk cells, no tile cache), RTF layer injection available", fl.displayName());
                return true;
            }

            tileCacheClass = Class.forName(fl.cls("world.worldgen.densityfunction.tile.TileCache"));
            tileClass = Class.forName(fl.cls("world.worldgen.densityfunction.tile.Tile"));
            tileChunkClass = Class.forName(fl.cls("world.worldgen.densityfunction.tile.Tile$Chunk"));

            cacheField = generatorContextClass.getField("cache");

            try {
                cacheProvideMethod = tileCacheClass.getMethod("provideAtChunk", int.class, int.class);
            } catch (NoSuchMethodException noProvide) {
                cacheProvideMethod = null; // lease-based FreeTerraForged has no blocking provideAtChunk
            }
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
                    try {
                        // FreeTerraForged: provideIfPresent works in tile coordinates.
                        cachePeekMethod = tileCacheClass.getMethod("provideIfPresent", int.class, int.class);
                        chunkToTileMethod = tileCacheClass.getMethod("chunkToTile", int.class);
                        cachePeekReturnsOptional = false;
                        cachePeekTakesTileCoords = true;
                        AronaLayersGen.LOGGER.info("[RTF] Found TileCache.provideIfPresent() + chunkToTile() - will use non-blocking tile access");
                    } catch (NoSuchMethodException e3) {
                      try {
                        // FreeTerraForged lease API: acquireIfPresent(tileX, tileZ) -> Lease{tile(), close()}
                        cachePeekMethod = tileCacheClass.getMethod("acquireIfPresent", int.class, int.class);
                        chunkToTileMethod = tileCacheClass.getMethod("chunkToTile", int.class);
                        Class<?> leaseClass = cachePeekMethod.getReturnType();
                        leaseTileMethod = leaseClass.getMethod("tile");
                        leaseCloseMethod = leaseClass.getMethod("close");
                        try {
                            leaseAcquireBlockingMethod = tileCacheClass.getMethod("acquire", int.class, int.class);
                        } catch (NoSuchMethodException noAcquire) {
                            leaseAcquireBlockingMethod = null;
                        }
                        cachePeekReturnsOptional = false;
                        cachePeekTakesTileCoords = true;
                        leaseMode = true;
                        AronaLayersGen.LOGGER.info("[RTF] Found TileCache.acquireIfPresent() lease API - will use non-blocking leased tile access");
                      } catch (NoSuchMethodException e4) {
                        cachePeekMethod = null;
                        AronaLayersGen.LOGGER.warn("[RTF] No non-blocking tile accessor found, falling back to provideAtChunk (may cause deadlocks)");
                        if (cacheProvideMethod == null) throw e4;
                      }
                    }
                }
            }
            getChunkReaderMethod = tileClass.getMethod("getChunkReader", int.class, int.class);
            getCellMethod = tileChunkClass.getMethod("getCell", int.class, int.class);

            generatorField = generatorContextClass.getField("generator");
            Class<?> heightmapClass = Class.forName(fl.cls("world.worldgen.cell.heightmap.Heightmap"));
            getHeightmapMethod = heightmapClass.getMethod("levels");
            Class<?> levelsClass = Class.forName(fl.cls("world.worldgen.cell.heightmap.Levels"));
            // Vertical scale that maps a normalised cell height to a block Y (Levels.scale()).
            // ReTerraForged: scale(h) = h * worldHeight. FreeTerraForged split the two: worldHeight
            // is now the world's vertical extent and scale(h) = h * terrainScaleFactor. Using
            // worldHeight on FTF puts every predicted surface hundreds of blocks too high, so the
            // conservative guard rejects it and stamps the fallback count everywhere.
            try {
                worldHeightField = levelsClass.getField("terrainScaleFactor");
                AronaLayersGen.LOGGER.info("[RTF] Using Levels.terrainScaleFactor as the height scale (FreeTerraForged)");
            } catch (NoSuchFieldException e) {
                worldHeightField = levelsClass.getField("worldHeight");
            }

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

            AronaLayersGen.LOGGER.info("{} bound, RTF layer injection available", fl.displayName());
            return true;
        } catch (Exception e) {
            AronaLayersGen.LOGGER.warn("Failed to initialize RTF compatibility: {}", e.getMessage());
            return false;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Binds the per-chunk cell API of FreeTerraForged without tiles. Throws if any part is missing. */
    private static void bindChunkCells(Flavor fl) throws ReflectiveOperationException {
        chunkCellsHolderClass = Class.forName(fl.cls("world.worldgen.IChunkCellsHolder"));
        String prefix = fl.modId() + "$";
        getChunkCellsMethod = chunkCellsHolderClass.getMethod(prefix + "getChunkCells");
        getOrCreateChunkCellsMethod = chunkCellsHolderClass.getMethod(prefix + "getOrCreateChunkCells", generatorContextClass);
        filteredChunkCacheField = generatorContextClass.getField("filteredChunkCache");
        getExistingFilteredCellMethod = filteredChunkCacheField.getType().getMethod("getExistingFilteredCell", int.class, int.class);
        levelsField = generatorContextClass.getField("levels");
        // Same height scale as the tile path below: terrainScaleFactor on FreeTerraForged.
        Class<?> levelsClass = levelsField.getType();
        try {
            worldHeightField = levelsClass.getField("terrainScaleFactor");
        } catch (NoSuchFieldException e) {
            worldHeightField = levelsClass.getField("worldHeight");
        }
    }

    /** A cell as the injector sees it, or null. */
    private static RTFLayerInjector.CellData cellData(Object cell) {
        if (cell == null) return null;
        try {
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
    }

    /**
     * Chunk-cells mode: reads the 256 cells FreeTerraForged keeps on the chunk. When a chunk still
     * being generated has none, they are rebuilt from the filtered chunk cache. A finished
     * LevelChunk (the LevelChunk.&lt;init&gt; pass runs on a new chunk object), or any chunk on the
     * server thread, only takes cells the cache still holds, one at a time: a miss leaves that
     * column without a cell rather than generating a region.
     */
    private static boolean withChunkCells(ChunkAccess chunk, Object generatorContext, CellsUser user) throws Exception {
        if (cachedWorldHeight < 0) {
            cachedWorldHeight = 256;
            try {
                Object levels = levelsField.get(generatorContext);
                if (levels != null) {
                    cachedWorldHeight = worldHeightField.getInt(levels);
                    AronaLayersGen.LOGGER.info("[RTF] Resolved height scale from Levels.{}: {}", worldHeightField.getName(), cachedWorldHeight);
                }
            } catch (Exception e) {
                AronaLayersGen.LOGGER.warn("[RTF] Could not get the height scale from Levels, using fallback 256: {}", e.getMessage());
            }
        }

        Object[] cells = null;
        if (chunkCellsHolderClass.isInstance(chunk)) {
            cells = (Object[]) getChunkCellsMethod.invoke(chunk);
            // A finished LevelChunk only reads cells the cache still holds. getOrCreateChunkCells
            // would store them on the chunk for as long as it stays loaded (68 KB each, kept alive
            // after the 2048-entry cache drops them), and on a cache miss rebuild a whole 4x4-chunk
            // region just for this re-check: the main placement already ran during generation.
            if (cells == null && !isServerThread() && !(chunk instanceof LevelChunk)) {
                cells = (Object[]) getOrCreateChunkCellsMethod.invoke(chunk, generatorContext);
            }
        }
        Object filteredCache = cells == null ? filteredChunkCacheField.get(generatorContext) : null;
        if (cells == null && filteredCache == null) {
            if (!nullCacheWarned) {
                nullCacheWarned = true;
                AronaLayersGen.LOGGER.warn("[RTF] Neither chunk cells nor a filtered chunk cache - RTF injection skipped.");
            }
            return false;
        }

        if (LayerConfig.logRtf()) {
            AronaLayersGen.LOGGER.info("[RTF] Processing chunk at {},{} with worldHeight={} ({})",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), cachedWorldHeight,
                cells != null ? "chunk cells" : "existing filtered cells");
        }

        final Object[] chunkCells = cells;
        user.use(cachedWorldHeight, (worldX, worldZ) -> {
            try {
                return cellData(chunkCells != null
                    ? chunkCells[(worldZ & 15) * 16 + (worldX & 15)]
                    : getExistingFilteredCellMethod.invoke(filteredCache, worldX, worldZ));
            } catch (Exception e) {
                return null;
            }
        });
        return true;
    }

    /** Receives a chunk's cells: the cell-height to block-Y scale and the cell data by world x and z. */
    @FunctionalInterface
    public interface CellsUser {
        void use(int scale, RTFLayerInjector.CellAccessor cells);
    }

    /**
     * Inject layers using RTF terrain data.
     *
     * @param chunk The chunk to process
     * @param randomState The RandomState (may be RTFRandomState)
     * @return true if RTF injection was performed
     */
    public static boolean injectLayersWithRTF(ChunkAccess chunk, Object randomState) {
        return withCells(chunk, randomState, (scale, cells) -> RTFLayerInjector.injectLayersForChunk(chunk, scale, cells));
    }

    /**
     * Opens the TerraForged cells of {@code chunk} and hands them to {@code user}, without blocking
     * on the server thread. The cells are only valid inside the call (a tile lease is released
     * after it).
     *
     * @return false if the cells could not be reached (reason logged once or under debug_log_rtf)
     */
    public static boolean withCells(ChunkAccess chunk, Object randomState, CellsUser user) {
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

            if (chunkCellsMode) return withChunkCells(chunk, generatorContext, user);

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
                                AronaLayersGen.LOGGER.info("[RTF] Resolved height scale from Levels.{}: {}", worldHeightField.getName(), cachedWorldHeight);
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

            Object handle = getTileNonBlocking(tileCache, chunkX, chunkZ);
            if (handle == null) return false;
            // In lease mode `handle` is a Lease: keep it open for the whole pass, close in finally.
            try {
            Object tile = leaseMode ? leaseTileMethod.invoke(handle) : handle;

            Object tileChunk = getChunkReaderMethod.invoke(tile, chunkX, chunkZ);
            if (tileChunk == null) return false;

            if (LayerConfig.logRtf()) {
                AronaLayersGen.LOGGER.info("[RTF] Processing chunk at {},{} with worldHeight={}", chunkX, chunkZ, finalWorldHeight);
            }

            user.use(finalWorldHeight, (worldX, worldZ) -> {
                try {
                    return cellData(getCellMethod.invoke(tileChunk, worldX, worldZ));
                } catch (Exception e) {
                    return null;
                }
            });

            return true;
            } finally {
                if (leaseMode) {
                    try { leaseCloseMethod.invoke(handle); } catch (Exception closeFail) {
                        AronaLayersGen.LOGGER.warn("[RTF] Failed to release tile lease at {},{}: {}", chunkX, chunkZ, closeFail.toString());
                    }
                }
            }
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
            Object result;
            if (cachePeekTakesTileCoords) {
                // FreeTerraForged provideIfPresent(tileX, tileZ): convert chunk -> tile first.
                int tileX = (int) chunkToTileMethod.invoke(tileCache, chunkX);
                int tileZ = (int) chunkToTileMethod.invoke(tileCache, chunkZ);
                result = cachePeekMethod.invoke(tileCache, tileX, tileZ);
                // FreeTerraForged's lease cache holds only 4-32 tiles with a short TTL, so under busy
                // worldgen the chunk's tile is often already evicted by its feature step. A miss used
                // to leave the chunk bare (the vanilla fallback is a no-op on RTF worlds). FTF's own
                // worldgen uses the blocking acquire() on worker threads - it just regenerates the
                // tile - so do the same, but never on the server thread.
                if (result == null && leaseMode && leaseAcquireBlockingMethod != null && !isServerThread()) {
                    result = leaseAcquireBlockingMethod.invoke(tileCache, tileX, tileZ);
                    if (LayerConfig.logRtf()) {
                        AronaLayersGen.LOGGER.info("[RTF] Tile {},{} evicted from FTF cache - regenerated via acquire()", tileX, tileZ);
                    }
                }
            } else {
                result = cachePeekMethod.invoke(tileCache, chunkX, chunkZ);
            }
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

    private static boolean isServerThread() {
        return "Server thread".equals(Thread.currentThread().getName());
    }
}
