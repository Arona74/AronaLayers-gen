package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.BlockMappingRegistry;
import io.arona74.aronalayersgen.FoliageMappingRegistry;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.PlantMappingRegistry;
import io.arona74.aronalayersgen.RockMappingRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;

/**
 * Shared helper for layer placement logic used by both RTF and vanilla injectors.
 * Contains registry access, property handling, decoration placement, structure detection,
 * and the core per-column injection logic.
 *
 * Supports two layer backends:
 * - Conquest Reforged (conquest mod): cr_block_mappings.json, full feature set
 *   (rocks, foliage, plant conversion, custom "layer" property 1-4)
 * - VanillaLayerPlus (vanillalayerplus mod): vp_block_mappings.json, basic layers only
 *   (uses standard vanilla LAYERS property 1-8)
 *
 * If neither mod is present, no block mappings are available and injection is a no-op.
 */
public class LayerPlacementHelper {

    // ========== Mod Backend Detection ==========

    public enum ModBackend {
        CONQUEST_REFORGED,
        VANILLA_LAYER_PLUS,
        NONE
    }

    private static ModBackend detectedBackend = null;

    public static ModBackend getBackend() {
        if (detectedBackend == null) {
            FabricLoader loader = FabricLoader.getInstance();
            if (loader.isModLoaded("conquest")) {
                detectedBackend = ModBackend.CONQUEST_REFORGED;
                AronaLayersGen.LOGGER.info("[AronaLayersGen] Detected backend: Conquest Reforged");
            } else if (loader.isModLoaded("vanillalayerplus")) {
                detectedBackend = ModBackend.VANILLA_LAYER_PLUS;
                AronaLayersGen.LOGGER.info("[AronaLayersGen] Detected backend: VanillaLayerPlus");
            } else {
                detectedBackend = ModBackend.NONE;
                AronaLayersGen.LOGGER.warn("[AronaLayersGen] No supported layer mod detected (conquest or vanillalayerplus). Layer injection will be inactive.");
            }
        }
        return detectedBackend;
    }

    public static boolean isConquestReforged() {
        return getBackend() == ModBackend.CONQUEST_REFORGED;
    }

    // ========== Registry Access ==========

    private static BlockMappingRegistry mappingRegistry;

    public static BlockMappingRegistry getMappingRegistry() {
        if (mappingRegistry == null) {
            String configFile = switch (getBackend()) {
                case CONQUEST_REFORGED -> "cr_block_mappings.json";
                case VANILLA_LAYER_PLUS -> "vp_block_mappings.json";
                case NONE -> "cr_block_mappings.json"; // fallback, will return empty
            };
            mappingRegistry = new BlockMappingRegistry(configFile);
        }
        return mappingRegistry;
    }

    public static boolean hasMappingFor(Block surfaceBlock) {
        return getMappingRegistry().hasMapping(surfaceBlock);
    }

    public static Block getMappedLayerBlock(Block surfaceBlock, int layerCount) {
        return getMappingRegistry().getLayerBlock(surfaceBlock, layerCount);
    }

    private static PlantMappingRegistry plantRegistry;

    public static PlantMappingRegistry getPlantRegistry() {
        if (plantRegistry == null) {
            plantRegistry = new PlantMappingRegistry();
        }
        return plantRegistry;
    }

    private static RockMappingRegistry rockRegistry;

    public static RockMappingRegistry getRockRegistry() {
        if (rockRegistry == null) {
            rockRegistry = new RockMappingRegistry();
        }
        return rockRegistry;
    }

    private static FoliageMappingRegistry foliageRegistry;

    public static FoliageMappingRegistry getFoliageRegistry() {
        if (foliageRegistry == null) {
            foliageRegistry = new FoliageMappingRegistry();
        }
        return foliageRegistry;
    }

    // ========== Property Handling ==========

    // Cache for CR's custom "layer" property lookup per block (values 1-4)
    private static final ConcurrentHashMap<Block, IntProperty> crLayerPropertyCache = new ConcurrentHashMap<>();

    /**
     * Find CR's custom "layer" IntProperty on a block (values 1-4).
     * Returns null if the block doesn't have it (e.g. VP blocks use vanilla LAYERS).
     */
    public static IntProperty getCRLayerProperty(Block block) {
        return crLayerPropertyCache.computeIfAbsent(block, b -> {
            for (Property<?> prop : b.getStateManager().getProperties()) {
                if (prop.getName().equals("layer") && prop instanceof IntProperty ip) {
                    return ip;
                }
            }
            return null;
        });
    }

    /**
     * Map calculated snow-layer count (1-8) to CR's "layer" property (1-4).
     * CR mapping: layer 1=1, 2=2, 3=4, 4=6 snow-layer equivalents.
     */
    public static int snowLayersToCRLayer(int layers) {
        if (layers <= 1) return 1;
        if (layers <= 3) return 2;
        if (layers <= 5) return 3;
        return 4;
    }

    /**
     * Map CR's "layer" property (1-4) back to snow-layer equivalents (1-8).
     */
    public static int crLayerToSnowLayers(int crLayer) {
        return switch (crLayer) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            case 4 -> 6;
            default -> 1;
        };
    }

    /**
     * Apply the appropriate layer property to a block state.
     * Handles both vanilla LAYERS (1-8) and CR's custom "layer" (1-4).
     */
    public static BlockState applyLayerCount(BlockState state, Block block, int layerCount) {
        if (state.contains(Properties.LAYERS)) {
            return state.with(Properties.LAYERS, layerCount);
        }
        IntProperty crProp = getCRLayerProperty(block);
        if (crProp != null) {
            return state.with(crProp, snowLayersToCRLayer(layerCount));
        }
        return state;
    }

    /**
     * Read the layer count from a block state as a snow-layer equivalent (1-8).
     * Handles both vanilla LAYERS and CR's custom "layer".
     */
    public static int readLayerCount(BlockState state) {
        if (state.contains(Properties.LAYERS)) {
            return state.get(Properties.LAYERS);
        }
        IntProperty crProp = getCRLayerProperty(state.getBlock());
        if (crProp != null) {
            return crLayerToSnowLayers(state.get(crProp));
        }
        return 1;
    }

    /**
     * Check if a block state has any layer property (vanilla LAYERS or CR "layer").
     */
    public static boolean hasLayerProperty(BlockState state) {
        if (state.contains(Properties.LAYERS)) return true;
        return getCRLayerProperty(state.getBlock()) != null;
    }

    // ========== Block Utilities ==========

    public static boolean isTallPlant(BlockState state) {
        return state.contains(Properties.DOUBLE_BLOCK_HALF);
    }

    // Cache for full-block lookups (layer block -> full block equivalent)
    private static final ConcurrentHashMap<Block, Block> fullBlockCache = new ConcurrentHashMap<>();

    /**
     * Derive the full-block equivalent of a layer/slab block by removing
     * common suffixes (_slab, _layer) from the block ID.
     */
    public static Block getFullBlock(Block layerBlock) {
        Block result = fullBlockCache.computeIfAbsent(layerBlock, b -> {
            net.minecraft.util.Identifier layerId = net.minecraft.registry.Registries.BLOCK.getId(b);
            String path = layerId.getPath();
            String namespace = layerId.getNamespace();
            for (String suffix : new String[]{"_slab", "_layer"}) {
                if (path.endsWith(suffix)) {
                    String fullPath = path.substring(0, path.length() - suffix.length());
                    net.minecraft.util.Identifier fullId = new net.minecraft.util.Identifier(namespace, fullPath);
                    Block fullBlock = net.minecraft.registry.Registries.BLOCK.get(fullId);
                    if (fullBlock != Blocks.AIR) return fullBlock;
                }
            }
            return Blocks.AIR; // sentinel for "not found" since ConcurrentHashMap disallows null values
        });
        return result == Blocks.AIR ? null : result;
    }

    // Cache for density property lookup per block
    private static final ConcurrentHashMap<Block, IntProperty> densityPropertyCache = new ConcurrentHashMap<>();

    /**
     * Find the "density" IntProperty on a block (used by CR rock blocks).
     * Returns null if the block doesn't have it.
     */
    public static IntProperty getDensityProperty(Block block) {
        return densityPropertyCache.computeIfAbsent(block, b -> {
            for (Property<?> prop : b.getStateManager().getProperties()) {
                if (prop.getName().equals("density") && prop instanceof IntProperty ip) {
                    return ip;
                }
            }
            return null;
        });
    }

    // ========== Hashing Utilities ==========

    public static long mixHash(int x, int z, long seed) {
        long h = seed ^ ((long) x * 0xFF51AFD7ED558CCDL) ^ ((long) z * 0xC4CEB9FE1A85EC53L);
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    public static int calculateRockDensity(int worldX, int worldZ) {
        long densityHash = mixHash(worldX, worldZ, 0x517CC1B727220A95L);
        if (LayerConfig.ROCK_DENSITY_MODE == LayerConfig.RockDensityMode.RANDOM) {
            int raw = (int) ((densityHash >>> 16) & 0x3);
            return 1 + raw;
        } else {
            float raw = ((densityHash >>> 16) & 0xFFFF) / 65536.0f;
            float f = LayerConfig.ROCK_DENSITY_FACTOR;
            float w1 = 1.0f, w2 = 1.0f / f, w3 = 1.0f / (f * f), w4 = 1.0f / (f * f * f);
            float total = w1 + w2 + w3 + w4;
            float t1 = w1 / total;
            float t2 = (w1 + w2) / total;
            float t3 = (w1 + w2 + w3) / total;
            if (raw < t1) return 1;
            if (raw < t2) return 2;
            if (raw < t3) return 3;
            return 4;
        }
    }

    // ========== Decoration Placement (Conquest Reforged only) ==========

    public static void tryPlaceRock(Chunk chunk, BlockPos rockPos, Block surfaceBlock, int worldX, int worldZ, BlockPos layerReadPos) {
        if (!isConquestReforged()) return;
        if (!getRockRegistry().hasMapping(surfaceBlock)) return;

        BlockState aboveState = chunk.getBlockState(rockPos);
        boolean underwaterRock = aboveState.getBlock() == Blocks.WATER;
        if (!aboveState.isAir() && !underwaterRock) return;

        long hash = mixHash(worldX, worldZ, 0x9E3779B97F4A7C15L);
        float chance = ((hash >>> 16) & 0xFFFF) / 65536.0f;
        if (chance >= LayerConfig.CHANCE_TO_PLACE_ROCKS) return;

        Block rockBlock = getRockRegistry().getRockBlock(surfaceBlock);
        if (rockBlock == null) return;

        int density = calculateRockDensity(worldX, worldZ);

        BlockState rockState = rockBlock.getDefaultState();

        int layerCount = 8;
        if (layerReadPos != null) {
            layerCount = readLayerCount(chunk.getBlockState(layerReadPos));
        }
        rockState = applyLayerCount(rockState, rockBlock, layerCount);

        IntProperty densityProp = getDensityProperty(rockBlock);
        if (densityProp != null) {
            density = Math.max(densityProp.getValues().stream().mapToInt(Integer::intValue).min().orElse(1),
                     Math.min(density, densityProp.getValues().stream().mapToInt(Integer::intValue).max().orElse(4)));
            rockState = rockState.with(densityProp, density);
        }

        if (underwaterRock) {
            if (rockState.contains(Properties.WATERLOGGED)) {
                rockState = rockState.with(Properties.WATERLOGGED, true);
            } else {
                return;
            }
        }

        setBlockStateSafe(chunk, rockPos, rockState);

        if (LayerConfig.DEBUG_LOGGING) {
            AronaLayersGen.LOGGER.info("[Rock] Placed {} with density {} at {}",
                net.minecraft.registry.Registries.BLOCK.getId(rockBlock), density, rockPos);
        }
    }

    public static void tryPlaceExtraFoliage(Chunk chunk, BlockPos foliagePos, Block surfaceBlock, int worldX, int worldZ, BlockPos layerReadPos) {
        if (!isConquestReforged()) return;
        if (!getFoliageRegistry().hasMapping(surfaceBlock)) return;

        BlockState aboveState = chunk.getBlockState(foliagePos);
        if (!aboveState.isAir()) return;

        long hash = mixHash(worldX, worldZ, 0x6C62272E07BB0142L);
        float chance = ((hash >>> 16) & 0xFFFF) / 65536.0f;
        if (chance >= LayerConfig.CHANCE_TO_PLACE_EXTRA_FOLIAGE) return;

        Block foliageBlock = getFoliageRegistry().getFoliageBlock(surfaceBlock);
        if (foliageBlock == null) return;

        BlockState foliageState = foliageBlock.getDefaultState();

        int layerCount = 8;
        if (layerReadPos != null) {
            layerCount = readLayerCount(chunk.getBlockState(layerReadPos));
        }
        foliageState = applyLayerCount(foliageState, foliageBlock, layerCount);

        setBlockStateSafe(chunk, foliagePos, foliageState);

        if (LayerConfig.DEBUG_LOGGING) {
            AronaLayersGen.LOGGER.info("[Foliage] Placed {} at {}",
                net.minecraft.registry.Registries.BLOCK.getId(foliageBlock), foliagePos);
        }
    }

    // ========== Structure Detection ==========

    private static final java.util.Set<Block> STRUCTURE_BLOCKS = java.util.Set.of(
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS,
        Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
        Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS,
        Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS,
        Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
        Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
        Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_BIRCH_LOG,
        Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG,
        Blocks.BRICKS, Blocks.STONE, Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE,
        Blocks.POLISHED_DIORITE, Blocks.POLISHED_GRANITE, Blocks.DEEPSLATE_BRICKS,
        Blocks.DEEPSLATE_TILES, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE,
        Blocks.RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE, Blocks.NETHER_BRICKS,
        Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
        Blocks.END_STONE_BRICKS
    );

    public static boolean isStructureBlock(Block block) {
        return STRUCTURE_BLOCKS.contains(block);
    }

    private static final ThreadLocal<List<BlockBox>> structureBoundsLocal = new ThreadLocal<>();

    public static void prepareStructureBounds(Chunk chunk, ChunkRegion region) {
        if (!LayerConfig.STRUCTURE_INJECTION) {
            structureBoundsLocal.remove();
            return;
        }

        List<BlockBox> bounds = new ArrayList<>();

        try {
            if (LayerConfig.DEBUG_LOGGING) {
                AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: getStructureStarts() count={} thread={}",
                    chunk.getPos().x, chunk.getPos().z,
                    chunk.getStructureStarts().size(),
                    Thread.currentThread().getName());
            }

            for (StructureStart start : chunk.getStructureStarts().values()) {
                if (start == StructureStart.DEFAULT) continue;
                if (LayerConfig.DEBUG_LOGGING) {
                    AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: collecting start={} pieces={}",
                        chunk.getPos().x, chunk.getPos().z,
                        start.getClass().getSimpleName(),
                        start.getChildren().size());
                }
                for (StructurePiece piece : start.getChildren()) {
                    bounds.add(piece.getBoundingBox());
                }
            }

            if (LayerConfig.DEBUG_LOGGING) {
                AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: starts done, boxes={}",
                    chunk.getPos().x, chunk.getPos().z, bounds.size());
            }

            // Only resolve cross-chunk structure references when a ChunkRegion is available.
            // Passing null (POST_FEATURES / WorldChunk context) skips this loop entirely —
            // iterating refs with a null region would throw a NullPointerException for every
            // reference entry, which is very expensive near large structures.
            if (region != null && LayerConfig.CROSS_CHUNK_STRUCTURE_DETECTION) {
                Map<Structure, LongSet> refs = chunk.getStructureReferences();
                for (Map.Entry<Structure, LongSet> entry : refs.entrySet()) {
                    Structure structure = entry.getKey();
                    for (long packedPos : entry.getValue()) {
                        int refChunkX = ChunkPos.getPackedX(packedPos);
                        int refChunkZ = ChunkPos.getPackedZ(packedPos);
                        try {
                            Chunk refChunk = region.getChunk(refChunkX, refChunkZ);
                            if (refChunk != null) {
                                StructureStart start = refChunk.getStructureStart(structure);
                                if (start != null && start != StructureStart.DEFAULT) {
                                    for (StructurePiece piece : start.getChildren()) {
                                        bounds.add(piece.getBoundingBox());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Neighboring chunk not available, skip
                        }
                    }
                }
            }
        } catch (Exception e) {
            AronaLayersGen.LOGGER.debug("[Structure] Error collecting structure bounds: {}", e.getMessage());
        }

        if (LayerConfig.DEBUG_LOGGING) {
            AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: prepareStructureBounds complete, total boxes={}",
                chunk.getPos().x, chunk.getPos().z, bounds.size());
        }

        // Always set the ThreadLocal, even when empty. A null value means
        // prepareStructureBounds was never called on this thread for this chunk,
        // which causes isInsideStructure to fall back to a per-call scan.
        // An empty list means "called and found nothing" — isInsideStructure returns false immediately.
        structureBoundsLocal.set(bounds);
    }

    public static void clearStructureBounds() {
        structureBoundsLocal.remove();
    }

    public static boolean hasEnclosingCeiling(Chunk chunk, BlockPos pos, int maxHeight) {
        for (int dy = 1; dy <= maxHeight; dy++) {
            BlockPos checkPos = pos.up(dy);
            BlockState state = chunk.getBlockState(checkPos);

            if (state.isAir() || state.getBlock() == Blocks.WATER) continue;
            if (!state.isOpaque()) continue;

            return true;
        }
        return false;
    }

    public static boolean isInsideStructure(Chunk chunk, BlockPos pos) {
        List<BlockBox> bounds = structureBoundsLocal.get();
        if (bounds != null) {
            for (BlockBox box : bounds) {
                if (box.contains(pos)) return true;
            }
            return false;
        }

        try {
            for (StructureStart start : chunk.getStructureStarts().values()) {
                if (start == StructureStart.DEFAULT) continue;
                for (StructurePiece piece : start.getChildren()) {
                    if (piece.getBoundingBox().contains(pos)) return true;
                }
            }
        } catch (Exception e) {
            // Silently skip
        }
        return false;
    }

    public static boolean isWaterAt(Chunk chunk, BlockPos pos) {
        BlockState state = chunk.getBlockState(pos);
        if (state.getBlock() == Blocks.WATER) return true;
        return state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED);
    }

    // ========== Core Layer Placement ==========

    public static final AtomicInteger debugSkipSnowy = new AtomicInteger();
    public static final AtomicInteger debugSkipNoSurface = new AtomicInteger();
    public static final AtomicInteger debugSkipNoMapping = new AtomicInteger();
    public static final AtomicInteger debugSkipLayerZero = new AtomicInteger();
    public static final AtomicInteger debugSkipNoLayerBlock = new AtomicInteger();
    public static final AtomicInteger debugSkipNotAir = new AtomicInteger();
    public static final AtomicInteger debugSkipEnclosed = new AtomicInteger();

    /**
     * Inject a layer at a single position.
     * Core shared placement logic used by both RTF and vanilla injectors.
     *
     * @param chunk The chunk being generated
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param layerCount Pre-calculated layer count (0 = no layer, 1-8 = layer depth)
     * @param useSnowLayers If true, use vanilla snow layers instead of block mappings
     * @return true if a layer was placed
     */
    public static boolean injectLayerAt(Chunk chunk, int worldX, int worldZ, int layerCount, boolean useSnowLayers) {
        int localX = worldX & 15;
        int localZ = worldZ & 15;

        boolean isWorldChunk = chunk instanceof WorldChunk;
        Heightmap.Type hmType = isWorldChunk
            ? Heightmap.Type.OCEAN_FLOOR : Heightmap.Type.OCEAN_FLOOR_WG;
        int surfaceY = chunk.getHeightmap(hmType).get(localX, localZ);

        boolean isPostFeaturesContext = (LayerConfig.INJECTION_MODE == LayerConfig.InjectionMode.POST_FEATURES)
            || isWorldChunk;

        if (surfaceY <= chunk.getBottomY()) {
            debugSkipNoSurface.incrementAndGet();
            return false;
        }

        boolean isSnowyBiome = false;
        {
            RegistryEntry<Biome> biome = chunk.getBiomeForNoiseGen(localX >> 2, surfaceY >> 2, localZ >> 2);
            BlockPos biomePos = new BlockPos(worldX, surfaceY - 1, worldZ);
            isSnowyBiome = biome.value().isCold(biomePos);
        }

        // Peek ahead to detect powder_snow before the snowy-biome skip.
        // OCEAN_FLOOR_WG doesn't count powder_snow as solid, so it may sit at surfaceY
        // (one above the heightmap surface). Check both surfaceY-1 and surfaceY.
        // NOTE: do NOT gate on !useSnowLayers — RTF may pass useSnowLayers=true for cold
        // biomes, but powder_snow surfaces must still use the CR mapping path.
        boolean surfaceIsPowderSnow = getMappingRegistry().hasMapping(Blocks.POWDER_SNOW)
                && (chunk.getBlockState(new BlockPos(worldX, surfaceY - 1, worldZ)).getBlock() == Blocks.POWDER_SNOW
                 || chunk.getBlockState(new BlockPos(worldX, surfaceY,     worldZ)).getBlock() == Blocks.POWDER_SNOW);

        // Ice and water surfaces need the full CR mapping path (waterlogged layers).
        // Switching to vanilla snow layers (IMPROVE_SNOWY_BIOMES) would fail because
        // snow layers have no WATERLOGGED property.
        Block topBlock = chunk.getBlockState(new BlockPos(worldX, surfaceY - 1, worldZ)).getBlock();
        boolean surfaceIsIceOrWater = topBlock == Blocks.ICE
                || topBlock == Blocks.FROSTED_ICE
                || topBlock == Blocks.WATER;

        if (LayerConfig.SKIP_SNOWY_BIOMES && isSnowyBiome && !surfaceIsPowderSnow) {
            debugSkipSnowy.incrementAndGet();
            return false;
        }

        if (isSnowyBiome && LayerConfig.IMPROVE_SNOWY_BIOMES && !surfaceIsPowderSnow && !surfaceIsIceOrWater) {
            useSnowLayers = true;
        } else if (surfaceIsIceOrWater || surfaceIsPowderSnow) {
            // Override any caller-supplied useSnowLayers=true: these surfaces need the CR
            // mapping path. Ice/water columns need waterlogged layers; powder_snow columns
            // need the powder_snow_layer block placed on top of the powder_snow.
            useSnowLayers = false;
        }

        if (LayerConfig.DEBUG_LOGGING && surfaceIsIceOrWater) {
            AronaLayersGen.LOGGER.info("[IceTrace] ({},{}) surfaceY={} topBlock={} iceOrWater=true useSnowLayers={} layerCount={}",
                worldX, worldZ, surfaceY,
                net.minecraft.registry.Registries.BLOCK.getId(topBlock),
                useSnowLayers, layerCount);
        }

        BlockPos surfacePos = new BlockPos(worldX, surfaceY - 1, worldZ);
        BlockState surfaceState = chunk.getBlockState(surfacePos);
        Block surfaceBlock = surfaceState.getBlock();

        // If powder_snow is sitting on top of the detected surface block (because OCEAN_FLOOR_WG
        // didn't count it as solid), elevate surfacePos to the powder_snow block so the layer
        // is placed on top of it rather than replacing the underlying block.
        if (!useSnowLayers && surfaceBlock != Blocks.POWDER_SNOW) {
            BlockState oneAbove = chunk.getBlockState(surfacePos.up());
            if (oneAbove.getBlock() == Blocks.POWDER_SNOW && getMappingRegistry().hasMapping(Blocks.POWDER_SNOW)) {
                surfacePos = surfacePos.up();
                surfaceState = oneAbove;
                surfaceBlock = Blocks.POWDER_SNOW;
            }
        }
        // Scan upward through the full powder_snow stack so the layer lands on top,
        // not inside a multi-block-deep pile.
        if (surfaceBlock == Blocks.POWDER_SNOW) {
            while (true) {
                BlockState nextAbove = chunk.getBlockState(surfacePos.up());
                if (nextAbove.getBlock() != Blocks.POWDER_SNOW) break;
                surfacePos = surfacePos.up();
                surfaceState = nextAbove;
            }
        }

        if (useSnowLayers && surfaceBlock == Blocks.SNOW_BLOCK) {
            setBlockStateSafe(chunk, surfacePos, Blocks.SNOW.getDefaultState().with(Properties.LAYERS, 8));
            if (LayerConfig.DEBUG_LOGGING) {
                AronaLayersGen.LOGGER.info("[Snow] Converted snow_block to snow_layers(8) at {}", surfacePos);
            }
            return true;
        }

        if (isPostFeaturesContext && !getMappingRegistry().hasMapping(surfaceBlock)) {
            boolean found = false;
            for (int dy = 1; dy <= 30; dy++) {
                int checkY = surfaceY - 1 - dy;
                if (checkY <= chunk.getBottomY()) break;

                BlockPos checkPos = new BlockPos(worldX, checkY, worldZ);
                BlockState checkState = chunk.getBlockState(checkPos);

                if (getMappingRegistry().hasMapping(checkState.getBlock())) {
                    surfacePos = checkPos;
                    surfaceState = checkState;
                    surfaceBlock = checkState.getBlock();
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (LayerConfig.DEBUG_LOGGING && surfaceIsIceOrWater) {
                    AronaLayersGen.LOGGER.info("[IceTrace] ({},{}) fallback scan found nothing -> skip", worldX, worldZ);
                }
                debugSkipNoMapping.incrementAndGet();
                return false;
            }
            if (LayerConfig.DEBUG_LOGGING && surfaceIsIceOrWater) {
                AronaLayersGen.LOGGER.info("[IceTrace] ({},{}) fallback scan found {} at Y={}",
                    worldX, worldZ,
                    net.minecraft.registry.Registries.BLOCK.getId(surfaceBlock),
                    surfacePos.getY());
            }
        }

        if (!getMappingRegistry().hasMapping(surfaceBlock)) {
            debugSkipNoMapping.incrementAndGet();
            return false;
        }

        boolean layerPlaced = false;

        if (layerCount <= 0) {
            debugSkipLayerZero.incrementAndGet();
        }

        BlockPos abovePos = surfacePos.up();

        if (LayerConfig.STRUCTURE_INJECTION && isInsideStructure(chunk, abovePos)) {
            return false;
        }

        if (LayerConfig.STRUCTURE_INJECTION && LayerConfig.ENCLOSED_SPACE_CHECK) {
            if (hasEnclosingCeiling(chunk, abovePos, LayerConfig.ENCLOSED_SPACE_HEIGHT)) {
                debugSkipEnclosed.incrementAndGet();
                return false;
            }
        }

        BlockState existingState = chunk.getBlockState(abovePos);
        // Treat ice as frozen water: allow placement (replacing ice) and waterlog the layer.
        boolean underwater = existingState.getBlock() == Blocks.WATER
                || existingState.getBlock() == Blocks.ICE
                || existingState.getBlock() == Blocks.FROSTED_ICE;
        boolean isPowderSnow = existingState.getBlock() == Blocks.POWDER_SNOW;

        Block replacedPlant = null;
        BlockState replacedPlantState = null;
        if (layerCount > 0) {
            Block layerBlock = useSnowLayers ? Blocks.SNOW : getMappingRegistry().getLayerBlock(surfaceBlock, layerCount);
            // powder_snow_layer(8) triggers onBlockAdded -> world.setBlockState(NOTIFY_ALL) which
            // cascades block updates during WorldChunk init and can hang the server tick.
            // Place the full block (powder_snow) directly instead; it's equivalent to 8 layers.
            if (layerBlock == AronaLayersGen.POWDER_SNOW_LAYER && layerCount >= 8) {
                layerBlock = Blocks.POWDER_SNOW;
            }
            if (layerBlock == null) {
                debugSkipNoLayerBlock.incrementAndGet();
            } else {
                boolean replacedTallPlant = false;
                BlockState savedTallUpperState = null;
                if (!existingState.isAir() && !underwater && !isPowderSnow) {
                    boolean isSnowBlockReplacement = useSnowLayers
                        && existingState.getBlock() == Blocks.SNOW_BLOCK;

                    boolean isSeagrass = existingState.getBlock() == Blocks.SEAGRASS
                        || existingState.getBlock() == Blocks.TALL_SEAGRASS;
                    boolean isReplaceablePlant = isPostFeaturesContext
                        && getPlantRegistry().isReplaceablePlant(existingState.getBlock())
                        && (!isSeagrass || LayerConfig.REPLACE_SEA_GRASS);
                    // CR: converts plant to Conquest equivalent placed above the layer
                    boolean canReplacePlant = isConquestReforged() && isReplaceablePlant;
                    // VP: shifts plant one block up so it sits on the layer (visual offset handled by VP)
                    boolean canShiftPlant = getBackend() == ModBackend.VANILLA_LAYER_PLUS && isReplaceablePlant;

                    if (isSnowBlockReplacement) {
                        // Snow_block will be overwritten by the snow layer below
                    } else if (canReplacePlant) {
                        replacedTallPlant = isTallPlant(existingState);
                        if (isSeagrass) {
                            // Seagrass needs water above for the CR equivalent; bail if at the water surface edge
                            BlockPos waterCheckPos = replacedTallPlant ? abovePos.up().up() : abovePos.up();
                            if (chunk.getBlockState(waterCheckPos).getBlock() != Blocks.WATER) {
                                debugSkipNotAir.incrementAndGet();
                                return false;
                            }
                        }
                        replacedPlant = existingState.getBlock();
                        if (replacedTallPlant) {
                            setBlockStateSafe(chunk, abovePos.up(), Blocks.AIR.getDefaultState());
                        }
                    } else if (canShiftPlant) {
                        replacedTallPlant = isTallPlant(existingState);
                        if (isSeagrass) {
                            // Seagrass needs water above to shift into; bail if at the water surface edge
                            BlockPos waterCheckPos = replacedTallPlant ? abovePos.up().up() : abovePos.up();
                            if (chunk.getBlockState(waterCheckPos).getBlock() != Blocks.WATER) {
                                debugSkipNotAir.incrementAndGet();
                                return false;
                            }
                        }
                        replacedPlantState = existingState;
                        if (replacedTallPlant) {
                            savedTallUpperState = chunk.getBlockState(abovePos.up());
                            setBlockStateSafe(chunk, abovePos.up(), Blocks.AIR.getDefaultState());
                        }
                    } else {
                        debugSkipNotAir.incrementAndGet();
                        return false;
                    }
                }

                BlockState layerState = layerBlock.getDefaultState();
                layerState = applyLayerCount(layerState, layerBlock, layerCount);

                // Seagrass replacement (CR or VP): water above was verified in detection; waterlog the layer
                Block seagrassCheck = replacedPlantState != null ? replacedPlantState.getBlock() : replacedPlant;
                if (seagrassCheck != null
                        && (seagrassCheck == Blocks.SEAGRASS || seagrassCheck == Blocks.TALL_SEAGRASS)
                        && layerState.contains(Properties.WATERLOGGED)) {
                    layerState = layerState.with(Properties.WATERLOGGED, true);
                }

                if (underwater) {
                    if (layerState.contains(Properties.WATERLOGGED)) {
                        layerState = layerState.with(Properties.WATERLOGGED, true);
                    }
                    // Block doesn't support waterlogging: place it anyway.
                    // The water/ice at abovePos is displaced, but all water above the layer
                    // remains, so the layer still appears correctly at the riverbed/lakebed.
                    if (LayerConfig.DEBUG_LOGGING) {
                        AronaLayersGen.LOGGER.info("[IceTrace] ({},{}) placing underwater layer {} at Y={} (waterlogged={})",
                            worldX, worldZ,
                            net.minecraft.registry.Registries.BLOCK.getId(layerState.getBlock()),
                            abovePos.getY(),
                            layerState.contains(Properties.WATERLOGGED) && layerState.get(Properties.WATERLOGGED));
                    }
                }

                setBlockStateSafe(chunk, abovePos, layerState);
                layerPlaced = true;

                if (LayerConfig.REPLACE_DIRT_PATH && surfaceBlock == Blocks.DIRT_PATH) {
                    Block fullBlock = getFullBlock(layerBlock);
                    if (fullBlock != null) {
                        setBlockStateSafe(chunk, surfacePos, fullBlock.getDefaultState());
                        if (LayerConfig.DEBUG_LOGGING) {
                            AronaLayersGen.LOGGER.info("[DirtPath] Replaced dirt_path at {} with {}",
                                surfacePos, net.minecraft.registry.Registries.BLOCK.getId(fullBlock));
                        }
                    }
                }

                // VP: shift plant one block up so it sits on the layer (visual offset handled by VP)
                if (replacedPlantState != null && LayerConfig.PLANT_INJECTION) {
                    BlockPos plantPos = abovePos.up();
                    Block shiftedBlock = replacedPlantState.getBlock();
                    boolean needsWater = shiftedBlock == Blocks.SEAGRASS || shiftedBlock == Blocks.TALL_SEAGRASS;
                    // For tall seagrass, plantPos was cleared to AIR; check plantPos.up() for water instead
                    BlockPos waterCheckPos = (needsWater && replacedTallPlant) ? plantPos.up() : plantPos;
                    if (!needsWater || chunk.getBlockState(waterCheckPos).getBlock() == Blocks.WATER) {
                        setBlockStateSafe(chunk, plantPos, replacedPlantState);
                        if (replacedTallPlant && savedTallUpperState != null) {
                            setBlockStateSafe(chunk, plantPos.up(), savedTallUpperState);
                        }
                    }
                }

                // Plant conversion: only supported with Conquest Reforged
                if (replacedPlant != null && isConquestReforged() && LayerConfig.PLANT_INJECTION) {
                    Block conquestPlant = getPlantRegistry().getConquestPlant(replacedPlant);
                    if (conquestPlant != null) {
                        BlockPos plantPos = abovePos.up();
                        BlockState conquestState = conquestPlant.getDefaultState();
                        conquestState = applyLayerCount(conquestState, conquestPlant, layerCount);

                        if (replacedTallPlant) {
                            if (conquestState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                                conquestState = conquestState.with(Properties.DOUBLE_BLOCK_HALF,
                                    net.minecraft.block.enums.DoubleBlockHalf.LOWER);
                            }
                            setBlockStateSafe(chunk, plantPos, conquestState);

                            BlockPos upperPos = plantPos.up();
                            BlockState upperState = conquestPlant.getDefaultState();
                            upperState = applyLayerCount(upperState, conquestPlant, layerCount);
                            if (upperState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                                upperState = upperState.with(Properties.DOUBLE_BLOCK_HALF,
                                    net.minecraft.block.enums.DoubleBlockHalf.UPPER);
                            }
                            setBlockStateSafe(chunk, upperPos, upperState);
                        } else {
                            setBlockStateSafe(chunk, plantPos, conquestState);
                        }
                    }
                }
            }
        } else {
            if (!existingState.isAir() && !underwater && !isPowderSnow) {
                return false;
            }
        }

        BlockPos decorPos = layerPlaced ? abovePos.up() : abovePos;

        if (replacedPlant == null && LayerConfig.PLACE_ROCKS) {
            tryPlaceRock(chunk, decorPos, surfaceBlock, worldX, worldZ, layerPlaced ? abovePos : null);
        }

        if (replacedPlant == null && LayerConfig.PLACE_EXTRA_FOLIAGE) {
            tryPlaceExtraFoliage(chunk, decorPos, surfaceBlock, worldX, worldZ, layerPlaced ? abovePos : null);
        }

        return layerPlaced;
    }

    /**
     * Sets a block state in the chunk without triggering Block.onBlockAdded notifications.
     *
     * When chunk is a WorldChunk, WorldChunk.setBlockState calls Block.onBlockAdded.
     * Some blocks (e.g. Conquest Reforged plants) call back into world.setBlockState
     * on neighbor positions inside onBlockAdded, which cascades into getChunkBlocking
     * and deadlocks the server thread during WorldChunk initialization.
     *
     * Writing directly to the chunk section bypasses onBlockAdded entirely.
     * Heightmaps are updated manually so surface/lighting data stays correct.
     */
    private static void setBlockStateSafe(Chunk chunk, BlockPos pos, BlockState state) {
        if (chunk instanceof WorldChunk) {
            int y = pos.getY();
            int sectionIdx = chunk.getSectionIndex(y);
            if (sectionIdx < 0 || sectionIdx >= chunk.countVerticalSections()) return;
            int lx = pos.getX() & 15, lz = pos.getZ() & 15;
            chunk.getSection(sectionIdx).setBlockState(lx, y & 15, lz, state);
            for (Map.Entry<Heightmap.Type, Heightmap> entry : chunk.getHeightmaps()) {
                entry.getValue().trackUpdate(lx, y, lz, state);
            }
        } else {
            setBlockStateSafe(chunk, pos, state);
        }
    }

    // ========== Correction Passes ==========

    public static void correctMismatchedLayers(Chunk chunk) {
        BlockMappingRegistry registry = getMappingRegistry();
        int corrected = 0;
        int removed = 0;
        int waterlogged = 0;
        int structureCleanup = 0;

        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;

                int heightmapY = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR).get(localX, localZ);
                if (heightmapY <= chunk.getBottomY() + 1) continue;

                BlockPos layerPos = null;
                BlockPos surfacePos = null;
                BlockState layerState = null;

                BlockPos pos1 = new BlockPos(worldX, heightmapY - 1, worldZ);
                BlockState state1 = chunk.getBlockState(pos1);

                if (hasLayerProperty(state1) && state1.getBlock() != Blocks.SNOW) {
                    layerPos = pos1;
                    layerState = state1;
                    surfacePos = pos1.down();
                } else {
                    BlockPos pos2 = new BlockPos(worldX, heightmapY, worldZ);
                    BlockState state2 = chunk.getBlockState(pos2);
                    if (hasLayerProperty(state2) && state2.getBlock() != Blocks.SNOW) {
                        layerPos = pos2;
                        layerState = state2;
                        surfacePos = pos1;
                    }
                }

                if (layerPos == null || surfacePos == null) continue;

                if (LayerConfig.STRUCTURE_INJECTION && isInsideStructure(chunk, layerPos)) {
                    setBlockStateSafe(chunk, layerPos, Blocks.AIR.getDefaultState());
                    removed++;
                    continue;
                }

                if (LayerConfig.STRUCTURE_INJECTION && LayerConfig.STRUCTURE_CLEANUP) {
                    boolean shouldRemove = false;

                    BlockState aboveState = chunk.getBlockState(layerPos.up());
                    if (!aboveState.isAir() && aboveState.isOpaque()) {
                        shouldRemove = true;
                    }

                    if (!shouldRemove) {
                        Block surfaceBlock = chunk.getBlockState(surfacePos).getBlock();
                        if (isStructureBlock(surfaceBlock)) {
                            shouldRemove = true;
                        }
                    }

                    if (!shouldRemove && LayerConfig.ENCLOSED_SPACE_CHECK) {
                        if (hasEnclosingCeiling(chunk, layerPos, LayerConfig.ENCLOSED_SPACE_HEIGHT)) {
                            shouldRemove = true;
                        }
                    }

                    if (shouldRemove) {
                        setBlockStateSafe(chunk, layerPos, Blocks.AIR.getDefaultState());
                        structureCleanup++;
                        continue;
                    }
                }

                boolean waterAbove = isWaterAt(chunk, layerPos.up());
                if (waterAbove && !LayerConfig.UNDERWATER_LAYERS) {
                    // Layer was placed underwater (e.g. during CARVERS before water filled in)
                    // but underwater layers are disabled; remove it
                    setBlockStateSafe(chunk, layerPos, Blocks.AIR.getDefaultState());
                    removed++;
                    continue;
                }

                boolean shouldBeWaterlogged = false;
                if (LayerConfig.UNDERWATER_LAYERS) {
                    if (waterAbove) {
                        shouldBeWaterlogged = true;
                    } else {
                        for (BlockPos neighbor : new BlockPos[]{
                                layerPos.north(), layerPos.south(),
                                layerPos.east(), layerPos.west()}) {
                            if (isWaterAt(chunk, neighbor)) {
                                shouldBeWaterlogged = true;
                                break;
                            }
                        }
                    }
                }

                Block surfaceBlock = chunk.getBlockState(surfacePos).getBlock();
                Block currentLayerBlock = layerState.getBlock();
                boolean changed = false;

                if (registry.hasMapping(surfaceBlock)) {
                    int currentLayers = readLayerCount(layerState);
                    Block correctLayerBlock = registry.getLayerBlock(surfaceBlock, currentLayers);

                    if (correctLayerBlock != null && correctLayerBlock != currentLayerBlock) {
                        BlockState newState = correctLayerBlock.getDefaultState();
                        newState = applyLayerCount(newState, correctLayerBlock, currentLayers);
                        if (shouldBeWaterlogged && newState.contains(Properties.WATERLOGGED)) {
                            newState = newState.with(Properties.WATERLOGGED, true);
                        }
                        setBlockStateSafe(chunk, layerPos, newState);
                        corrected++;
                        changed = true;
                    }
                } else {
                    setBlockStateSafe(chunk, layerPos,
                        shouldBeWaterlogged ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState());
                    removed++;
                    changed = true;
                }

                if (!changed && shouldBeWaterlogged && layerState.contains(Properties.WATERLOGGED)
                        && !layerState.get(Properties.WATERLOGGED)) {
                    setBlockStateSafe(chunk, layerPos,
                        layerState.with(Properties.WATERLOGGED, true));
                    waterlogged++;
                }
            }
        }

        if ((corrected > 0 || removed > 0 || waterlogged > 0 || structureCleanup > 0) && LayerConfig.DEBUG_LOGGING) {
            AronaLayersGen.LOGGER.info("[Correction] Chunk {},{}: corrected={}, removed={}, waterlogged={}, structureCleanup={}",
                chunk.getPos().x, chunk.getPos().z, corrected, removed, waterlogged, structureCleanup);
        }
    }

    public static void removeLayersAtColumns(Chunk chunk, Set<Integer> changedColumns) {
        int removed = 0;
        int preserved = 0;
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int index : changedColumns) {
            int localX = index % 16;
            int localZ = index / 16;
            int worldX = startX + localX;
            int worldZ = startZ + localZ;

            int heightmapY = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR).get(localX, localZ);
            if (heightmapY <= chunk.getBottomY() + 1) continue;

            for (int dy = -1; dy <= 0; dy++) {
                BlockPos pos = new BlockPos(worldX, heightmapY + dy, worldZ);
                BlockState state = chunk.getBlockState(pos);

                if (hasLayerProperty(state) && state.getBlock() != Blocks.SNOW) {
                    BlockState aboveState = chunk.getBlockState(pos.up());
                    if (!aboveState.isAir() && aboveState.getBlock() != Blocks.WATER) {
                        preserved++;
                        break;
                    }

                    setBlockStateSafe(chunk, pos, Blocks.AIR.getDefaultState());
                    removed++;
                    break;
                }
            }
        }

        if ((removed > 0 || preserved > 0) && LayerConfig.DEBUG_LOGGING) {
            AronaLayersGen.LOGGER.info("[StructureNoLayers] Chunk {},{}: removed={}, preserved={} (had plants above)",
                chunk.getPos().x, chunk.getPos().z, removed, preserved);
        }
    }
}
