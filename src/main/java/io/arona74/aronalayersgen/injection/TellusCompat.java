package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compatibility layer for Tellus (Earth-scale geo-data terrain) integration.
 * Uses reflection to access Tellus classes without a hard dependency.
 *
 * <p>Reflection handles are resolved lazily from the <em>live generator instance's</em>
 * own class, not via {@code Class.forName} on our classloader. This is deliberate: on
 * NeoForge each mod can load through its own module classloader, so a {@code forName}'d
 * {@code EarthChunkGenerator} may be a different {@code Class} object than the running
 * generator's, making {@code isInstance} spuriously false. Walking the instance's own
 * hierarchy sidesteps that entirely.
 *
 * <p>Tellus builds terrain from a real-world Digital Elevation Model. Its
 * {@code EarthChunkGenerator.scaleElevationToHeight()} converts continuous elevation in
 * metres to a block Y with {@code scaled = elevation * heightScale / verticalWorldScale}
 * then {@code ceil}/{@code floor} + {@code heightOffset}. The fractional part of
 * {@code scaled + heightOffset} is exactly the sub-block elevation our layer system
 * consumes — the same idea as ReTerraForged's normalized {@code Cell.height}. We re-sample
 * the same elevation source (a warm cache hit during generateFeatures) and apply the same
 * snow-layer fractional formula used by {@link RTFLayerInjector}.
 */
public class TellusCompat {

    private static boolean modPresentChecked = false;
    private static boolean modPresent = false;

    // Lazily bound from the first real generator instance we see.
    private static volatile boolean bound = false;
    private static volatile boolean bindFailed = false;

    private static Method settingsMethod;               // EarthChunkGenerator.settings()
    private static Object elevationSource;              // static TellusElevationSource
    private static Object landCoverSource;              // static TellusLandCoverSource

    private static Method worldScaleMethod;             // double worldScale()
    private static Method terrestrialHeightScaleMethod; // double effectiveTerrestrialHeightScale()
    private static Method oceanicHeightScaleMethod;     // double effectiveOceanicHeightScale()
    private static Method verticalWorldScaleMethod;     // double effectiveVerticalWorldScale()
    private static Method heightOffsetMethod;           // int effectiveHeightOffset()
    private static Method demSelectionMethod;           // DemSelection demSelection()

    private static Method sampleElevationMemoryOnly;    // (double,double,double,boolean,DemSelection,double)->double
    private static Method sampleElevationBlocking;      // (double,double,double)->double  (fallback)
    private static Method sampleOceanElevationMemoryOnly; // (double,double,double,DemSelection,double)->double
    private static Method sampleCoverClassMemoryOnly;   // (double,double,double,double)->int

    private static boolean loggedNotTellusGenerator = false;
    private static boolean loggedEngaged = false;

    private static final AtomicInteger debugSkipSubmerged = new AtomicInteger();
    private static final AtomicInteger debugSkipNoElevation = new AtomicInteger();

    /**
     * Chunks whose WG heightmap was still empty during the generateFeatures() pass, so nothing
     * could be placed. They are retried at WorldChunk.&lt;init&gt; once heightmaps are rebuilt.
     *
     * <p>Tracks failures rather than successes deliberately: failures are rare, so the set stays
     * small, and any entry that is never claimed (a chunk that never constructs a WorldChunk —
     * e.g. ResetChunksCommand reuses the existing one) costs almost nothing. Tracking successes
     * would instead grow with every chunk generated in the session.
     */
    private static final java.util.Set<Long> chunksNeedingRerun = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * True when the features pass could not resolve a surface for this chunk, so the caller
     * should run injection again. Consumes the record.
     */
    public static boolean needsRerun(Chunk chunk) {
        return chunksNeedingRerun.remove(chunk.getPos().toLong());
    }

    /** True when the Tellus mod is loaded. Cheap; does not touch reflection. */
    public static boolean isAvailable() {
        if (!modPresentChecked) {
            modPresentChecked = true;
            modPresent = FabricLoader.getInstance().isModLoaded("tellus");
            if (modPresent) {
                AronaLayersGen.LOGGER.info("[Tellus] Tellus mod detected — layer injection will bind on first Tellus chunk");
            } else {
                AronaLayersGen.LOGGER.info("[Tellus] Tellus mod not detected, Tellus layer injection disabled");
            }
        }
        return modPresent;
    }

    /** Resolve every reflection handle from the running generator's own class. */
    private static boolean bind(ChunkGenerator generator) {
        if (bound) return true;
        if (bindFailed) return false;

        synchronized (TellusCompat.class) {
            if (bound) return true;
            if (bindFailed) return false;

            // Find EarthChunkGenerator anywhere in the instance's own hierarchy.
            Class<?> c = generator.getClass();
            Class<?> found = null;
            while (c != null && c != Object.class) {
                if (c.getName().equals("com.yucareux.tellus.worldgen.EarthChunkGenerator")) {
                    found = c;
                    break;
                }
                c = c.getSuperclass();
            }
            if (found == null) {
                // Not a Tellus generator — a plain (non-Tellus) world. Not an error.
                if (!loggedNotTellusGenerator) {
                    loggedNotTellusGenerator = true;
                    AronaLayersGen.LOGGER.info("[Tellus] Active generator is not Tellus ({}); Tellus injection stands down for this world",
                        generator.getClass().getName());
                }
                return false;
            }

            try {
                ClassLoader cl = found.getClassLoader();

                settingsMethod = found.getMethod("settings");

                Field elevationField = found.getDeclaredField("ELEVATION_SOURCE");
                elevationField.setAccessible(true);
                elevationSource = elevationField.get(null);
                Field coverField = found.getDeclaredField("LAND_COVER_SOURCE");
                coverField.setAccessible(true);
                landCoverSource = coverField.get(null);

                Class<?> settingsClass = settingsMethod.getReturnType();
                worldScaleMethod = settingsClass.getMethod("worldScale");
                terrestrialHeightScaleMethod = settingsClass.getMethod("effectiveTerrestrialHeightScale");
                oceanicHeightScaleMethod = settingsClass.getMethod("effectiveOceanicHeightScale");
                verticalWorldScaleMethod = settingsClass.getMethod("effectiveVerticalWorldScale");
                heightOffsetMethod = settingsClass.getMethod("effectiveHeightOffset");
                demSelectionMethod = settingsClass.getMethod("demSelection");
                Class<?> demSelectionClass = demSelectionMethod.getReturnType();

                Class<?> elevationClass = elevationSource.getClass();
                Class<?> coverClass = landCoverSource.getClass();
                sampleElevationMemoryOnly = elevationClass.getMethod(
                    "samplePreviewElevationMetersMemoryOnly",
                    double.class, double.class, double.class, boolean.class, demSelectionClass, double.class);
                sampleElevationBlocking = elevationClass.getMethod(
                    "sampleElevationMeters", double.class, double.class, double.class);
                // Bathymetry, for columns under water. Optional: older Tellus builds may lack it.
                try {
                    sampleOceanElevationMemoryOnly = elevationClass.getMethod(
                        "samplePreviewOceanElevationMetersMemoryOnly",
                        double.class, double.class, double.class, demSelectionClass, double.class);
                } catch (NoSuchMethodException e) {
                    sampleOceanElevationMemoryOnly = null;
                    AronaLayersGen.LOGGER.warn("[Tellus] Ocean elevation sampler unavailable; underwater layers will use the land DEM");
                }
                sampleCoverClassMemoryOnly = coverClass.getMethod(
                    "sampleCoverClassMemoryOnly",
                    double.class, double.class, double.class, double.class);

                bound = true;
                AronaLayersGen.LOGGER.info("[Tellus] Bound to EarthChunkGenerator (loader={}) — Tellus layer injection engaged",
                    cl);
                return true;
            } catch (Throwable t) {
                bindFailed = true;
                AronaLayersGen.LOGGER.error("[Tellus] Failed to bind reflection handles — Tellus injection disabled: {}", t.toString(), t);
                return false;
            }
        }
    }

    /**
     * Inject layers for a chunk using Tellus elevation + land-cover data.
     *
     * @return true if Tellus handled this chunk (whether or not any layer was placed);
     *         false means the caller should fall through to another backend.
     */
    public static boolean injectLayersWithTellus(Chunk chunk, ChunkGenerator generator) {
        if (!isAvailable() || generator == null) {
            return false;
        }
        if (!bind(generator)) {
            return false;
        }

        try {
            Object settings = settingsMethod.invoke(generator);
            double worldScale = (double) worldScaleMethod.invoke(settings);
            double terrestrialScale = (double) terrestrialHeightScaleMethod.invoke(settings);
            double oceanicScale = (double) oceanicHeightScaleMethod.invoke(settings);
            double verticalScale = (double) verticalWorldScaleMethod.invoke(settings);
            int heightOffset = (int) heightOffsetMethod.invoke(settings);
            Object demSelection = demSelectionMethod.invoke(settings);

            if (!loggedEngaged) {
                loggedEngaged = true;
                AronaLayersGen.LOGGER.info("[Tellus] First chunk: worldScale={}, terrestrialScale={}, verticalScale={}, heightOffset={} | structureSkipElevated={}, conservativeSurface={}, structureInjection={}",
                    worldScale, terrestrialScale, verticalScale, heightOffset,
                    LayerConfig.STRUCTURE_SKIP_ELEVATED, LayerConfig.CONSERVATIVE_SURFACE_HEIGHTMAP, LayerConfig.STRUCTURE_INJECTION);
            }

            debugSkipSubmerged.set(0);
            debugSkipNoElevation.set(0);
            LayerPlacementHelper.debugSkipSnowy.set(0);
            LayerPlacementHelper.debugSkipNoSurface.set(0);
            LayerPlacementHelper.debugSkipNoMapping.set(0);
            LayerPlacementHelper.debugSkipLayerZero.set(0);
            LayerPlacementHelper.debugSkipNoLayerBlock.set(0);
            LayerPlacementHelper.debugSkipNotAir.set(0);
            LayerPlacementHelper.debugSkipEnclosed.set(0);

            int startX = chunk.getPos().getStartX();
            int startZ = chunk.getPos().getStartZ();

            Heightmap.Type floorType = (chunk instanceof WorldChunk)
                ? Heightmap.Type.OCEAN_FLOOR : Heightmap.Type.OCEAN_FLOOR_WG;
            int layersPlaced = 0;

            // Concurrency-safe local diagnostics (the shared debugSkip* statics are
            // corrupted by C2ME running chunks in parallel). Rebuilds the failure
            // breakdown by inspecting each column's own blocks.
            int locSubmerged = 0, locNoElev = 0, locLayerGe1 = 0, locLayer0 = 0;
            int locNoSurface = 0, locNoMapping = 0, locAboveBlocked = 0, locAboveAir = 0, locAbovePlant = 0;
            int locDemMismatch = 0;
            int locHasSurface = 0;
            int locNoBedData = 0;
            boolean loggedWaterColumn = false;

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldX = startX + localX;
                    int worldZ = startZ + localZ;

                    int floorY = chunk.getHeightmap(floorType).get(localX, localZ);

                    // Test for an actual fluid directly above the solid floor. The heightmap
                    // heuristic (WORLD_SURFACE > OCEAN_FLOOR + 1) counts ANY non-air block, so a
                    // two-block plant like tall_grass reads as "submerged" — which disabled the
                    // structure guards and, with underwater_layers off, skipped the column outright.
                    boolean isSubmerged = !chunk.getBlockState(new BlockPos(worldX, floorY, worldZ))
                        .getFluidState().isEmpty();
                    if (isSubmerged && !LayerConfig.UNDERWATER_LAYERS) {
                        debugSkipSubmerged.incrementAndGet();
                        locSubmerged++;
                        continue;
                    }

                    // Re-sample the elevation Tellus already used for this column.
                    // Memory-only never blocks; on a (rare) miss, fall back to the blocking
                    // sampler so the column still gets a layer rather than being dropped.
                    //
                    // Submerged columns must come from bathymetry: the land DEM describes the
                    // water surface, not the bed, so using it would put the expected base far
                    // above the actual floor and make every underwater column look "lowered".
                    double elevation = Double.NaN;
                    boolean fromBathymetry = false;
                    if (isSubmerged && sampleOceanElevationMemoryOnly != null) {
                        elevation = (double) sampleOceanElevationMemoryOnly.invoke(
                            elevationSource, (double) worldX, (double) worldZ, worldScale,
                            demSelection, worldScale);
                        fromBathymetry = !Double.isNaN(elevation);
                    }
                    if (Double.isNaN(elevation)) {
                        elevation = (double) sampleElevationMemoryOnly.invoke(
                            elevationSource, (double) worldX, (double) worldZ, worldScale,
                            false, demSelection, worldScale);
                    }
                    if (Double.isNaN(elevation)) {
                        elevation = (double) sampleElevationBlocking.invoke(
                            elevationSource, (double) worldX, (double) worldZ, worldScale);
                    }
                    if (Double.isNaN(elevation)) {
                        debugSkipNoElevation.incrementAndGet();
                        locNoElev++;
                        continue;
                    }

                    // Underwater layers require a real bed elevation. Bathymetry (ocean/sea)
                    // supplies it; inland water (lakes, rivers, ponds) has none, and the land DEM
                    // fallback here describes the water surface / surrounding terrain, not the bed
                    // — so any layer value derived from it is meaningless (was giving layers whose
                    // thickness was unrelated to the actual floor). Skip rather than place a
                    // wrong-valued layer.
                    if (isSubmerged && !fromBathymetry) {
                        locNoBedData++;
                        continue;
                    }

                    double heightScale = elevation >= 0.0 ? terrestrialScale : oceanicScale;
                    double scaled = elevation * heightScale / verticalScale;
                    double continuous = scaled + heightOffset;
                    // The sub-block fraction that drives the layer count.
                    double depth = continuous - Math.floor(continuous); // [0..1)
                    // expectedBaseY must equal the Y at which Tellus actually places the top
                    // solid block, so STRUCTURE_SKIP_ELEVATED / CONSERVATIVE_SURFACE_HEIGHTMAP
                    // only trip on real structure edits — not on the systematic ceil-vs-floor
                    // offset. Mirror EarthChunkGenerator.scaleElevationToHeight() exactly:
                    // ceil for land, floor for ocean, integer heightOffset added afterwards.
                    int expectedBaseY = (elevation >= 0.0 ? (int) Math.ceil(scaled) : (int) Math.floor(scaled)) + heightOffset;

                    // Submerged columns that reach this point are bathymetry-backed (inland water
                    // was skipped above). Still disable the structure/conservative guards for them:
                    // Tellus reshapes the raw bathymetry before building it (coastal safety ramp,
                    // optional depth compression, monotonic fitting into the vertical range,
                    // reserved support blocks), so the sampled value never matches the bed it
                    // actually places, and the guard would otherwise clamp every column to a
                    // constant fallback.
                    int baseForGuards = isSubmerged ? Integer.MIN_VALUE : expectedBaseY;

                    int coverClass = (int) sampleCoverClassMemoryOnly.invoke(
                        landCoverSource, (double) worldX, (double) worldZ, worldScale, worldScale);

                    boolean isSnowyBiome = false;
                    if (floorY > chunk.getBottomY()) {
                        var biome = chunk.getBiomeForNoiseGen(localX >> 2, floorY >> 2, localZ >> 2);
                        isSnowyBiome = biome.value().isCold(new BlockPos(worldX, floorY, worldZ));
                    }
                    boolean useSnowLayers = (isSnowyBiome || coverClass == 70) && LayerConfig.IMPROVE_SNOWY_BIOMES;

                    // Layer-count reduction is decoupled from the snow decision: snow columns
                    // always keep the raw value, and non-snow columns keep it too unless
                    // tellus_reduce_layer_count is on. This stops improve_snowy_biomes from
                    // silently changing material-layer thickness.
                    boolean skipReduction = useSnowLayers || !LayerConfig.TELLUS_REDUCE_LAYER_COUNT;
                    int layerCount = calculateLayerCount(depth, skipReduction);
                    if (layerCount >= 1) locLayerGe1++; else locLayer0++;

                    // Pre-inspect this column the way injectLayerAt will, so we can attribute
                    // a non-placement to a concrete cause (surface Y from OCEAN_FLOOR heightmap,
                    // i.e. our floorY, matches what injectLayerAt reads for a WorldChunk).
                    String surfaceId = "?", aboveId = "?";
                    boolean surfaceMapped = false;
                    boolean hasSurface = floorY > chunk.getBottomY();
                    if (hasSurface) locHasSurface++;
                    if (hasSurface) {
                        var surfaceState = chunk.getBlockState(new BlockPos(worldX, floorY - 1, worldZ));
                        var aboveState = chunk.getBlockState(new BlockPos(worldX, floorY, worldZ));
                        surfaceId = String.valueOf(net.minecraft.registry.Registries.BLOCK.getId(surfaceState.getBlock()));
                        aboveId = String.valueOf(net.minecraft.registry.Registries.BLOCK.getId(aboveState.getBlock()));
                        surfaceMapped = LayerPlacementHelper.hasMappingFor(surfaceState.getBlock());
                        // Tellus's own scaleElevationToHeight() is ceil(scaled)+offset, so for a
                        // column we sampled identically this must equal the real top solid block.
                        // A mismatch means our re-sample diverged from what Tellus actually built
                        // (terrain preload package, ocean-zoom DEM selection, or post-gen surface
                        // repair) — the fraction is then unrelated to this column's real surface.
                        if ((int) Math.ceil(continuous) != floorY - 1) {
                            locDemMismatch++;
                        }
                        if (layerCount >= 1) {
                            if (!surfaceMapped) {
                                locNoMapping++;
                            } else if (aboveState.isAir()) {
                                locAboveAir++;
                            } else if (aboveState.isReplaceable()) {
                                locAbovePlant++;
                            } else {
                                locAboveBlocked++;
                            }
                        }
                    } else if (layerCount >= 1) {
                        locNoSurface++;
                    }

                    boolean logCenter = LayerConfig.logTellus() && localX == 8 && localZ == 8;
                    boolean logWater = LayerConfig.logTellus() && isSubmerged && !loggedWaterColumn;
                    boolean placed = LayerPlacementHelper.injectLayerAtTellus(
                        chunk, worldX, worldZ, layerCount, useSnowLayers, baseForGuards);
                    if (placed) {
                        layersPlaced++;
                    }

                    // GEOMETRY PROBE: topSolidY is the real block Tellus placed. Comparing it
                    // against floor(cont)/ceil(cont) tells us Tellus's actual fill convention,
                    // which is what the layer model has to be calibrated against.
                    if (logCenter || logWater) {
                        if (logWater) loggedWaterColumn = true;
                        AronaLayersGen.LOGGER.info("[Tellus] {} ({},{}) elev={} bathy={} cont={} floor(cont)={} ceil(cont)={} topSolidY={} heightmapY={} surface={} above={} depth={} layers={} placed={} submerged={} cover={}",
                            isSubmerged ? "WATER " : "center",
                            worldX, worldZ,
                            String.format("%.2f", elevation), fromBathymetry,
                            String.format("%.3f", continuous),
                            (int) Math.floor(continuous), (int) Math.ceil(continuous),
                            floorY - 1, floorY, surfaceId, aboveId,
                            String.format("%.3f", depth), layerCount, placed, isSubmerged, coverClass);
                    }
                }
            }

            // No column resolved a surface: the heightmap was still empty at this point, so
            // nothing could be placed. Flag the chunk for a retry at WorldChunk.<init>, once
            // heightmaps have been rebuilt.
            if (locHasSurface == 0) {
                chunksNeedingRerun.add(chunk.getPos().toLong());
            }

            if (LayerConfig.logTellus()) {
                AronaLayersGen.LOGGER.info("[Tellus] Chunk {},{}: placed={} | cols layer>=1={} layer0={} | fail breakdown: noSurface={}, noMapping={}, aboveAir(should place)={}, abovePlant={}, aboveBlocked={} | submergedSkip={}, noBedData={}, noElev={}, demMismatch={}",
                    chunk.getPos().x, chunk.getPos().z, layersPlaced,
                    locLayerGe1, locLayer0,
                    locNoSurface, locNoMapping, locAboveAir, locAbovePlant, locAboveBlocked,
                    locSubmerged, locNoBedData, locNoElev, locDemMismatch);
            }
            return true;
        } catch (Throwable t) {
            AronaLayersGen.LOGGER.warn("[Tellus] Layer injection failed at {},{}: {}",
                chunk.getPos().x, chunk.getPos().z, t.toString());
            return false;
        }
    }

    /**
     * Fractional layer count: {@code layers = round(depth * 8)}. When {@code skipReduction} is
     * false one layer is subtracted (a legacy RTF aesthetic tweak). The caller decides the
     * reduction policy — it is no longer tied to whether snow layers are used (see the
     * {@code skipReduction} computation at the call site).
     */
    private static int calculateLayerCount(double depth, boolean skipReduction) {
        int layers = (int) Math.round(depth * 8.0);
        if (!skipReduction) {
            layers -= 1;
        }
        if (layers < 1) {
            return 0;
        }
        return Math.min(8, layers);
    }

    /**
     * Layer count for replace-surface mode.
     *
     * <p>Tellus's {@code scaleElevationToHeight()} is {@code ceil(scaled)+offset}, and that
     * value is the index of the top solid block. So the block sitting at {@code topSolidY}
     * represents a partial fill of {@code frac(cont)} that Tellus rounded up to a whole block —
     * the layer that replaces it should be exactly that fraction.
     *
     * <p>No {@code -1} reduction here: that term compensates the RTF path, where the layer is
     * stacked ON TOP of the surface and one eighth of slack is harmless. In replace mode it
     * subtracts real terrain height (depth 0.3 would render as 1/8 instead of ~2.4/8), which is
     * what made the first replace-mode attempt drop terrain by a block.
     *
     * <p>{@code depth} is how full block {@code topSolidY} SHOULD be, so the mapping is:
     * <ul>
     *   <li>{@code depth → 1} — block is essentially full, leave it as a solid block (return 0).</li>
     *   <li>{@code depth → 0} — block is essentially empty. It still has to be replaced, with the
     *       thinnest layer available: returning 0 here would leave a full block standing a whole
     *       block too tall, which is the single biggest visual error this mode exists to remove.
     *       A 1/8 layer keeps the surface material (grass stays grass) while dropping the column,
     *       whereas deleting the block outright would expose the dirt/stone underneath.</li>
     * </ul>
     *
     * @return 1..7 to replace the block with a layer, or 0 meaning "leave the full block alone"
     *         (only when the block is essentially full).
     */
    // ===================== Diagnostics probe =====================

    /** Everything the Tellus backend derives for a single column. Used by /algtellus. */
    public static final class Probe {
        public boolean valid;
        public String error = "";
        public int worldX, worldZ;
        public double elevation;
        public boolean bathymetry;
        public double scaled, continuous, depth;
        public int floorCont, ceilCont;
        /** Tellus's own scaleElevationToHeight() result — where it SHOULD have put the top block. */
        public int tellusSurfaceY;
        public int heightmapY;
        /** Top solid block straight from the heightmap — may include layers we placed earlier. */
        public int rawTopSolidY;
        /** Top solid block with previously-placed layer blocks walked off: the natural terrain. */
        public int actualTopSolidY;
        /** How many stacked layer blocks sat above the natural surface. */
        public int layersStripped;
        /** tellusSurfaceY - actualTopSolidY. Non-zero means our DEM re-sample diverged. */
        public int mismatch;
        public int coverClass;
        public boolean submerged, surfaceMapped;
        /** True when this column is skipped: submerged water with no bathymetry (no real bed). */
        public boolean skippedNoBedData;
        /** True when this is a Tellus snow column (snow_block / snow stack); our layer joins it. */
        public boolean snowColumn;
        /** Y of the topmost snow block in the stack. */
        public int snowStackTopY = Integer.MIN_VALUE;
        /** Human-readable snow stack, top-down (e.g. "snow[6]@139, snow[8]@138"). */
        public String snowStack = "";
        public String surfaceBlock = "-", aboveBlock = "-";
        /** Layer value the backend decides for this column; 0 = no layer. */
        public int stackLayers;
        public String existingLayer = "-";
        public int existingLayerValue;
        /** Y the layer should occupy: one above the terrain surface. */
        public int predictedLayerY;
        /** Y a layer block was actually found at, or Integer.MIN_VALUE for none. */
        public int foundLayerY = Integer.MIN_VALUE;
    }

    /**
     * Sample one column exactly the way {@link #injectLayersWithTellus} does, without
     * modifying anything. Safe to call post-generation from a command.
     */
    public static Probe probe(ChunkGenerator generator, Chunk chunk, int worldX, int worldZ) {
        Probe p = new Probe();
        p.worldX = worldX;
        p.worldZ = worldZ;

        if (!isAvailable()) { p.error = "Tellus mod not loaded"; return p; }
        if (generator == null || !bind(generator)) { p.error = "not a Tellus generator (or bind failed)"; return p; }

        try {
            Object settings = settingsMethod.invoke(generator);
            double worldScale = (double) worldScaleMethod.invoke(settings);
            double terrestrialScale = (double) terrestrialHeightScaleMethod.invoke(settings);
            double oceanicScale = (double) oceanicHeightScaleMethod.invoke(settings);
            double verticalScale = (double) verticalWorldScaleMethod.invoke(settings);
            int heightOffset = (int) heightOffsetMethod.invoke(settings);
            Object demSelection = demSelectionMethod.invoke(settings);

            int localX = worldX & 15, localZ = worldZ & 15;
            Heightmap.Type floorType = (chunk instanceof WorldChunk)
                ? Heightmap.Type.OCEAN_FLOOR : Heightmap.Type.OCEAN_FLOOR_WG;
            int floorY = chunk.getHeightmap(floorType).get(localX, localZ);
            p.heightmapY = floorY;
            p.rawTopSolidY = floorY - 1;

            // Some CR layer blocks (slab-style ones) count as solid for OCEAN_FLOOR, so a layer
            // this mod placed on an earlier run gets measured as terrain and inflates the
            // heightmap by a block. Walk down past any layer blocks to find the NATURAL top,
            // otherwise MISMATCH reports our own output as a DEM disagreement.
            int natural = floorY - 1;
            int guard = 0;
            while (natural > chunk.getBottomY() && guard++ < 8
                   && LayerPlacementHelper.hasLayerProperty(chunk.getBlockState(new BlockPos(worldX, natural, worldZ)))) {
                natural--;
            }
            p.actualTopSolidY = natural;
            p.layersStripped = (floorY - 1) - natural;

            p.submerged = !chunk.getBlockState(new BlockPos(worldX, floorY, worldZ)).getFluidState().isEmpty();

            double elevation = Double.NaN;
            if (p.submerged && sampleOceanElevationMemoryOnly != null) {
                elevation = (double) sampleOceanElevationMemoryOnly.invoke(
                    elevationSource, (double) worldX, (double) worldZ, worldScale, demSelection, worldScale);
                p.bathymetry = !Double.isNaN(elevation);
            }
            if (Double.isNaN(elevation)) {
                elevation = (double) sampleElevationMemoryOnly.invoke(
                    elevationSource, (double) worldX, (double) worldZ, worldScale, false, demSelection, worldScale);
            }
            if (Double.isNaN(elevation)) {
                elevation = (double) sampleElevationBlocking.invoke(
                    elevationSource, (double) worldX, (double) worldZ, worldScale);
            }
            if (Double.isNaN(elevation)) { p.error = "no elevation data"; return p; }

            p.skippedNoBedData = p.submerged && !p.bathymetry;
            p.elevation = elevation;
            double heightScale = elevation >= 0.0 ? terrestrialScale : oceanicScale;
            p.scaled = elevation * heightScale / verticalScale;
            p.continuous = p.scaled + heightOffset;
            p.depth = p.continuous - Math.floor(p.continuous);
            p.floorCont = (int) Math.floor(p.continuous);
            p.ceilCont = (int) Math.ceil(p.continuous);
            p.tellusSurfaceY = (elevation >= 0.0 ? (int) Math.ceil(p.scaled) : (int) Math.floor(p.scaled)) + heightOffset;
            // p.mismatch is finalised after the layer scan below: a layer we placed changes
            // where the natural surface appears to be, differently per mode.

            p.coverClass = (int) sampleCoverClassMemoryOnly.invoke(
                landCoverSource, (double) worldX, (double) worldZ, worldScale, worldScale);

            if (floorY > chunk.getBottomY()) {
                BlockState surfaceState = chunk.getBlockState(new BlockPos(worldX, floorY - 1, worldZ));
                BlockState aboveState = chunk.getBlockState(new BlockPos(worldX, floorY, worldZ));
                p.surfaceBlock = String.valueOf(net.minecraft.registry.Registries.BLOCK.getId(surfaceState.getBlock()));
                p.aboveBlock = String.valueOf(net.minecraft.registry.Registries.BLOCK.getId(aboveState.getBlock()));
                p.surfaceMapped = LayerPlacementHelper.hasMappingFor(surfaceState.getBlock());

                // Tellus snow: a snow_block (or, after we run, a snow[8]) covers the terrain, with
                // our snow-layer terrace stacked above it. Use WORLD_SURFACE (highest non-air) to
                // find the real top, mirroring the injector, and read the whole snow stack top-down.
                net.minecraft.world.Heightmap.Type wsType = (chunk instanceof WorldChunk)
                    ? net.minecraft.world.Heightmap.Type.WORLD_SURFACE
                    : net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG;
                int wsY = chunk.getHeightmap(wsType).get(worldX & 15, worldZ & 15);
                net.minecraft.block.Block topBlock = wsY > chunk.getBottomY()
                    ? chunk.getBlockState(new BlockPos(worldX, wsY - 1, worldZ)).getBlock()
                    : net.minecraft.block.Blocks.AIR;
                if (topBlock == net.minecraft.block.Blocks.SNOW_BLOCK || topBlock == net.minecraft.block.Blocks.SNOW) {
                    p.snowColumn = true;
                    p.snowStackTopY = wsY - 1;
                    StringBuilder stack = new StringBuilder();
                    for (int y = wsY - 1; y > chunk.getBottomY(); y--) {
                        BlockState s = chunk.getBlockState(new BlockPos(worldX, y, worldZ));
                        if (s.getBlock() == net.minecraft.block.Blocks.SNOW) {
                            int v = LayerPlacementHelper.readLayerCount(s);
                            if (stack.length() > 0) stack.append(", ");
                            stack.append("snow[").append(v).append("]@").append(y);
                        } else if (s.getBlock() == net.minecraft.block.Blocks.SNOW_BLOCK) {
                            if (stack.length() > 0) stack.append(", ");
                            stack.append("snow_block@").append(y);
                        } else {
                            break;
                        }
                    }
                    p.snowStack = stack.toString();
                    // Our terrace, once placed, is the topmost snow layer whose value is < 8 sitting
                    // above a full snow base. Report it as the found layer.
                    BlockState top = chunk.getBlockState(new BlockPos(worldX, wsY - 1, worldZ));
                    if (top.getBlock() == net.minecraft.block.Blocks.SNOW
                        && LayerPlacementHelper.readLayerCount(top) < 8) {
                        p.existingLayer = "minecraft:snow";
                        p.existingLayerValue = LayerPlacementHelper.readLayerCount(top);
                        p.foundLayerY = wsY - 1;
                    }
                } else {
                    // Find where a layer actually landed (stacking writes one above the surface).
                    for (int y = p.rawTopSolidY + 1; y >= p.rawTopSolidY - 2 && y > chunk.getBottomY(); y--) {
                        BlockState s = chunk.getBlockState(new BlockPos(worldX, y, worldZ));
                        if (LayerPlacementHelper.hasLayerProperty(s)) {
                            p.existingLayer = String.valueOf(net.minecraft.registry.Registries.BLOCK.getId(s.getBlock()));
                            p.existingLayerValue = LayerPlacementHelper.readLayerCount(s);
                            p.foundLayerY = y;
                            break;
                        }
                    }
                }
            }

            // Mirror the injector's reduction policy so the reported value matches what lands.
            p.stackLayers = calculateLayerCount(p.depth, p.snowColumn || !LayerConfig.TELLUS_REDUCE_LAYER_COUNT);
            p.predictedLayerY = p.tellusSurfaceY + 1;

            // Recover where the natural surface was BEFORE we wrote a layer, so MISMATCH
            // measures DEM-vs-terrain rather than our own edit: the layer sits one above
            // the surface, which is still the solid block.
            if (p.foundLayerY != Integer.MIN_VALUE) {
                p.actualTopSolidY = p.foundLayerY - 1;
            }
            p.mismatch = p.tellusSurfaceY - p.actualTopSolidY;
            p.valid = true;
            return p;
        } catch (Throwable t) {
            p.error = t.toString();
            return p;
        }
    }

}
