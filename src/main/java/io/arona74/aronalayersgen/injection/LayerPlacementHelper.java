package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
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
import net.minecraft.server.world.ServerWorld;
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
                    net.minecraft.util.Identifier fullId = Compat.id(namespace, fullPath);
                    Block fullBlock = net.minecraft.registry.Registries.BLOCK.get(fullId);
                    if (fullBlock != Blocks.AIR) return fullBlock;
                }
            }
            return Blocks.AIR; // sentinel for "not found" since ConcurrentHashMap disallows null values
        });
        return result == Blocks.AIR ? null : result;
    }

    // Cached block references for wet-sand substitution (Blocks.AIR = not found)
    private static volatile Block cachedSandLayerBlock = null;
    private static volatile Block cachedWetSandLayerBlock = null;

    /**
     * If layerBlock is conquest:sand_layer and conquest:wet_sand_layer exists, returns the wet variant.
     * Returns null otherwise (no substitution needed).
     */
    private static Block getWetSandSubstitute(Block layerBlock) {
        Block sand = cachedSandLayerBlock;
        if (sand == null) {
            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse("conquest:sand_layer");
            sand = (id != null) ? net.minecraft.registry.Registries.BLOCK.get(id) : Blocks.AIR;
            cachedSandLayerBlock = sand;
        }
        if (sand == Blocks.AIR || layerBlock != sand) return null;

        Block wet = cachedWetSandLayerBlock;
        if (wet == null) {
            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse("conquest:wet_sand_layer");
            wet = (id != null) ? net.minecraft.registry.Registries.BLOCK.get(id) : Blocks.AIR;
            cachedWetSandLayerBlock = wet;
        }
        return (wet != Blocks.AIR) ? wet : null;
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

        if (LayerConfig.logRocks()) {
            AronaLayersGen.LOGGER.info("[Rock] Placed {} with density {} at {}",
                net.minecraft.registry.Registries.BLOCK.getId(rockBlock), density, rockPos);
        }
    }

    /**
     * Try to place a rock using the per-biome enhanced registry.
     * Returns true if the biome is registered in the enhanced registry (meaning the basic
     * rock registry should be suppressed for this position regardless of whether a rock was placed).
     * Returns false if the biome is not in the enhanced registry (caller should fall back to tryPlaceRock).
     */
    public static boolean tryPlaceEnhancedRock(Chunk chunk, BlockPos rockPos, Block surfaceBlock, int worldX, int worldZ, BlockPos layerReadPos) {
        if (!isConquestReforged()) return false;

        net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biomeEntry =
            chunk.getBiomeForNoiseGen(worldX >> 2, rockPos.getY() >> 2, worldZ >> 2);
        net.minecraft.util.Identifier biomeId = biomeEntry.getKey()
            .map(k -> k.getValue())
            .orElse(null);
        if (biomeId == null) return false;

        io.arona74.aronalayersgen.EnhancedRockRegistry registry =
            io.arona74.aronalayersgen.EnhancedRockRegistry.getInstance();
        if (!registry.hasBiome(biomeId)) return false;

        // Biome is in the enhanced registry — take ownership; basic registry will not run.
        BlockState aboveState = chunk.getBlockState(rockPos);
        boolean underwaterRock = aboveState.getBlock() == Blocks.WATER;
        if (!aboveState.isAir() && !underwaterRock) return true;

        long hash = mixHash(worldX, worldZ, 0xB14C3A7F2D85E961L);
        float roll = ((hash >>> 16) & 0xFFFF) / 65536.0f;
        float biomeChance = registry.getChance(biomeId);
        float effectiveChance = biomeChance >= 0f ? biomeChance : LayerConfig.CHANCE_TO_PLACE_ROCKS;
        if (roll >= effectiveChance) return true;

        Block rockBlock = registry.selectRock(biomeId, surfaceBlock, hash);
        if (rockBlock == null) return true;

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
                return true;
            }
        }

        setBlockStateSafe(chunk, rockPos, rockState);

        if (LayerConfig.logRocks()) {
            AronaLayersGen.LOGGER.info("[EnhancedRock] Placed {} with density {} at {}",
                net.minecraft.registry.Registries.BLOCK.getId(rockBlock), density, rockPos);
        }
        return true;
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
        clearOrphanedPlantAbove(chunk, foliagePos);

        if (LayerConfig.logFoliage()) {
            AronaLayersGen.LOGGER.info("[Foliage] Placed {} at {}",
                net.minecraft.registry.Registries.BLOCK.getId(foliageBlock), foliagePos);
        }
    }

    public static void tryPlaceEnhancedFoliage(Chunk chunk, BlockPos foliagePos, Block surfaceBlock, int worldX, int worldZ, BlockPos layerReadPos) {
        if (!isConquestReforged()) return;

        BlockState aboveState = chunk.getBlockState(foliagePos);
        if (!aboveState.isAir()) return;

        long hash = mixHash(worldX, worldZ, 0xA3B4C5D6E7F80192L);
        float roll = ((hash >>> 16) & 0xFFFF) / 65536.0f;

        // Resolve biome at this column
        net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biomeEntry =
            chunk.getBiomeForNoiseGen(worldX >> 2, foliagePos.getY() >> 2, worldZ >> 2);
        net.minecraft.util.Identifier biomeId = biomeEntry.getKey()
            .map(k -> k.getValue())
            .orElse(null);
        if (biomeId == null) return;

        io.arona74.aronalayersgen.EnhancedFoliageRegistry registry =
            io.arona74.aronalayersgen.EnhancedFoliageRegistry.getInstance();
        float biomeChance = registry.getChance(biomeId);
        float effectiveChance = biomeChance >= 0f ? biomeChance : LayerConfig.CHANCE_TO_PLACE_EXTRA_FOLIAGE;
        if (roll >= effectiveChance) return;

        Block grassBlock = registry.selectGrass(biomeId, surfaceBlock, hash);
        if (grassBlock == null) return;

        BlockState grassState = grassBlock.getDefaultState();
        int layerCount = 8;
        if (layerReadPos != null) {
            layerCount = readLayerCount(chunk.getBlockState(layerReadPos));
        }
        grassState = applyLayerCount(grassState, grassBlock, layerCount);

        setBlockStateSafe(chunk, foliagePos, grassState);
        clearOrphanedPlantAbove(chunk, foliagePos);

        if (LayerConfig.logFoliage()) {
            AronaLayersGen.LOGGER.info("[EnhancedFoliage] Placed {} at {}",
                net.minecraft.registry.Registries.BLOCK.getId(grassBlock), foliagePos);
        }
    }

    /**
     * Clears a vanilla replaceable plant immediately above {@code pos} if present.
     * After placing a CR foliage block at {@code pos}, any vanilla plant one block
     * above is an orphan — its original support block was replaced by terrain
     * processing before our injection ran.
     */
    private static void clearOrphanedPlantAbove(Chunk chunk, BlockPos pos) {
        BlockPos above = pos.up();
        BlockState aboveState = chunk.getBlockState(above);
        if (!getPlantRegistry().isReplaceablePlant(aboveState.getBlock())) return;
        setBlockStateSafe(chunk, above, Blocks.AIR.getDefaultState());
        if (aboveState.contains(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF)) {
            setBlockStateSafe(chunk, above.up(), Blocks.AIR.getDefaultState());
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
    private static final ThreadLocal<List<BlockBox>> structureFootprintLocal = new ThreadLocal<>();

    public static void prepareStructureBounds(Chunk chunk, ChunkRegion region) {
        if (!LayerConfig.STRUCTURE_INJECTION) {
            structureBoundsLocal.remove();
            structureFootprintLocal.remove();
            return;
        }

        List<BlockBox> bounds = new ArrayList<>();

        try {
            if (LayerConfig.logStructure()) {
                AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: getStructureStarts() count={} thread={}",
                    chunk.getPos().x, chunk.getPos().z,
                    chunk.getStructureStarts().size(),
                    Thread.currentThread().getName());
            }

            for (StructureStart start : chunk.getStructureStarts().values()) {
                if (start == StructureStart.DEFAULT) continue;
                if (LayerConfig.logStructure()) {
                    AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: collecting start={} pieces={}",
                        chunk.getPos().x, chunk.getPos().z,
                        start.getClass().getSimpleName(),
                        start.getChildren().size());
                }
                for (StructurePiece piece : start.getChildren()) {
                    bounds.add(piece.getBoundingBox());
                }
            }

            if (LayerConfig.logStructure()) {
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

        if (LayerConfig.logStructure()) {
            AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: prepareStructureBounds complete, total boxes={}",
                chunk.getPos().x, chunk.getPos().z, bounds.size());
        }

        // Always set the ThreadLocal, even when empty. A null value means
        // prepareStructureBounds was never called on this thread for this chunk,
        // which causes isInsideStructure to fall back to a per-call scan.
        // An empty list means "called and found nothing" — isInsideStructure returns false immediately.
        structureBoundsLocal.set(bounds);

        // Footprint: hull of each StructureStart's pieces.
        List<BlockBox> footprints = new ArrayList<>();
        if (LayerConfig.STRUCTURE_FOOTPRINT_CHECK) {
            try {
                for (StructureStart start : chunk.getStructureStarts().values()) {
                    if (start == StructureStart.DEFAULT || start.getChildren().isEmpty()) continue;
                    footprints.add(start.getBoundingBox());
                }
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
                                    StructureStart refStart = refChunk.getStructureStart(structure);
                                    if (refStart != null && refStart != StructureStart.DEFAULT && !refStart.getChildren().isEmpty()) {
                                        footprints.add(refStart.getBoundingBox());
                                    }
                                }
                            } catch (Exception e) {
                                // Neighboring chunk not available, skip
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AronaLayersGen.LOGGER.debug("[Structure] Error collecting footprint bounds: {}", e.getMessage());
            }
        }
        structureFootprintLocal.set(footprints);
    }

    /**
     * Like prepareStructureBounds but for POST_FEATURES mode.
     * Uses ServerWorld.getChunkManager().getWorldChunk() (non-blocking, returns null if not loaded)
     * to resolve cross-chunk structure references without deadlock risk.
     * This catches village bounding boxes that started in a neighboring chunk.
     */
    public static void prepareStructureBoundsWithWorld(Chunk chunk, ServerWorld world) {
        if (!LayerConfig.STRUCTURE_INJECTION) {
            structureBoundsLocal.remove();
            structureFootprintLocal.remove();
            return;
        }

        List<BlockBox> bounds = new ArrayList<>();

        try {
            if (LayerConfig.logStructure()) {
                AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: getStructureStarts() count={} thread={}",
                    chunk.getPos().x, chunk.getPos().z,
                    chunk.getStructureStarts().size(),
                    Thread.currentThread().getName());
            }

            for (StructureStart start : chunk.getStructureStarts().values()) {
                if (start == StructureStart.DEFAULT) continue;
                if (LayerConfig.logStructure()) {
                    AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: collecting start={} pieces={}",
                        chunk.getPos().x, chunk.getPos().z,
                        start.getClass().getSimpleName(),
                        start.getChildren().size());
                }
                for (StructurePiece piece : start.getChildren()) {
                    bounds.add(piece.getBoundingBox());
                }
            }

            // Resolve cross-chunk structure references using ServerWorld.
            // getWorldChunk() is non-blocking (returns null if not loaded), so this is
            // safe in POST_FEATURES mode and cannot cause deadlocks or tick timeouts.
            // This catches structures like villages whose start chunk differs from this chunk.
            if (world != null) {
                Map<Structure, LongSet> refs = chunk.getStructureReferences();
                for (Map.Entry<Structure, LongSet> entry : refs.entrySet()) {
                    Structure structure = entry.getKey();
                    for (long packedPos : entry.getValue()) {
                        int refChunkX = ChunkPos.getPackedX(packedPos);
                        int refChunkZ = ChunkPos.getPackedZ(packedPos);
                        try {
                            WorldChunk refChunk = world.getChunkManager().getWorldChunk(refChunkX, refChunkZ);
                            if (refChunk != null) {
                                StructureStart refStart = refChunk.getStructureStart(structure);
                                if (refStart != null && refStart != StructureStart.DEFAULT) {
                                    if (LayerConfig.logStructure()) {
                                        AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: cross-chunk ref from {},{} start={} pieces={}",
                                            chunk.getPos().x, chunk.getPos().z,
                                            refChunkX, refChunkZ,
                                            refStart.getClass().getSimpleName(),
                                            refStart.getChildren().size());
                                    }
                                    for (StructurePiece piece : refStart.getChildren()) {
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

        if (LayerConfig.logStructure()) {
            AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: prepareStructureBoundsWithWorld complete, total boxes={}",
                chunk.getPos().x, chunk.getPos().z, bounds.size());
        }

        structureBoundsLocal.set(bounds);

        // Footprint: hull of each StructureStart's pieces.
        List<BlockBox> footprints = new ArrayList<>();
        if (LayerConfig.STRUCTURE_FOOTPRINT_CHECK) {
            try {
                for (StructureStart start : chunk.getStructureStarts().values()) {
                    if (start == StructureStart.DEFAULT || start.getChildren().isEmpty()) continue;
                    footprints.add(start.getBoundingBox());
                }
                if (world != null) {
                    Map<Structure, LongSet> refs = chunk.getStructureReferences();
                    for (Map.Entry<Structure, LongSet> entry : refs.entrySet()) {
                        Structure structure = entry.getKey();
                        for (long packedPos : entry.getValue()) {
                            int refChunkX = ChunkPos.getPackedX(packedPos);
                            int refChunkZ = ChunkPos.getPackedZ(packedPos);
                            try {
                                WorldChunk refChunk = world.getChunkManager().getWorldChunk(refChunkX, refChunkZ);
                                if (refChunk != null) {
                                    StructureStart refStart = refChunk.getStructureStart(structure);
                                    if (refStart != null && refStart != StructureStart.DEFAULT && !refStart.getChildren().isEmpty()) {
                                        footprints.add(refStart.getBoundingBox());
                                    }
                                }
                            } catch (Exception e) {
                                // Neighboring chunk not available, skip
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AronaLayersGen.LOGGER.debug("[Structure] Error collecting footprint bounds: {}", e.getMessage());
            }
        }
        structureFootprintLocal.set(footprints);
    }

    public static void clearStructureBounds() {
        structureBoundsLocal.remove();
        structureFootprintLocal.remove();
    }

    // ========== Second-Pass Cleanup ==========

    private static Set<Block> secondPassCleanupBlocks = null;

    private static Set<Block> getSecondPassCleanupBlocks() {
        if (secondPassCleanupBlocks == null) {
            secondPassCleanupBlocks = new java.util.HashSet<>();
            for (String id : io.arona74.aronalayersgen.ConfigLoader.loadBlockList("second_pass_cleanup_blocks.json")) {
                net.minecraft.util.Identifier identifier = net.minecraft.util.Identifier.tryParse(id);
                if (identifier == null) {
                    AronaLayersGen.LOGGER.warn("[SecondPass] Invalid block ID: {}", id);
                    continue;
                }
                Block block = net.minecraft.registry.Registries.BLOCK.get(identifier);
                if (block == Blocks.AIR) {
                    AronaLayersGen.LOGGER.warn("[SecondPass] Block not found: {}", id);
                    continue;
                }
                secondPassCleanupBlocks.add(block);
            }
            AronaLayersGen.LOGGER.info("[SecondPass] Loaded {} trigger block(s)", secondPassCleanupBlocks.size());
        }
        return secondPassCleanupBlocks;
    }

    /** Positions (worldX, worldZ, Y) where a layer was blocked/removed over a trigger surface block. */
    private static final ThreadLocal<List<int[]>> secondPassTargetsLocal = new ThreadLocal<>();

    /**
     * Record a gap position for second-pass tapering if the surface block is in the trigger set.
     * Only acts when SECOND_PASS_CLEANUP is enabled.
     */
    public static void addSecondPassTarget(int worldX, int worldZ, int y, Block surfaceBlock) {
        if (!LayerConfig.SECOND_PASS_CLEANUP) return;
        if (!getSecondPassCleanupBlocks().contains(surfaceBlock)) return;
        List<int[]> targets = secondPassTargetsLocal.get();
        if (targets == null) {
            targets = new ArrayList<>();
            secondPassTargetsLocal.set(targets);
        }
        targets.add(new int[]{worldX, worldZ, y});
    }

    /**
     * Taper layer values in the 8 in-chunk neighbors of every recorded gap position.
     * - count > 4  → set to 4
     * - count ≤ 4  → decrement by 1
     * - count reaches 0 → remove block, move plant above down by 1
     */
    public static void runSecondPassCleanup(Chunk chunk) {
        List<int[]> targets = secondPassTargetsLocal.get();
        secondPassTargetsLocal.remove();
        if (targets == null || targets.isEmpty()) return;

        ChunkPos chunkPos = chunk.getPos();
        int baseX = chunkPos.getStartX();
        int baseZ = chunkPos.getStartZ();
        int cleaned = 0;

        for (int[] target : targets) {
            int targetX = target[0];
            int targetZ = target[1];
            int targetY = target[2];

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    int nx = targetX + dx;
                    int nz = targetZ + dz;
                    int nlx = nx - baseX;
                    int nlz = nz - baseZ;
                    if (nlx < 0 || nlx >= 16 || nlz < 0 || nlz >= 16) continue;

                    // Scan ±2 Y range around the gap Y for a layer block
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos layerPos = new BlockPos(nx, targetY + dy, nz);
                        BlockState state = chunk.getBlockState(layerPos);
                        if (!hasLayerProperty(state) || state.getBlock() == Blocks.SNOW) continue;

                        int currentCount = readLayerCount(state);
                        int newCount = currentCount > 4 ? 4 : currentCount - 1;

                        if (newCount <= 0) {
                            setBlockStateSafe(chunk, layerPos, Blocks.AIR.getDefaultState());
                            // Move plant above down
                            BlockPos abovePos = layerPos.up();
                            BlockState aboveState = chunk.getBlockState(abovePos);
                            if (!aboveState.isAir() && aboveState.isReplaceable()) {
                                setBlockStateSafe(chunk, layerPos, aboveState);
                                setBlockStateSafe(chunk, abovePos, Blocks.AIR.getDefaultState());
                            }
                        } else {
                            setBlockStateSafe(chunk, layerPos, applyLayerCount(state, state.getBlock(), newCount));
                        }
                        cleaned++;
                        break; // one layer block per neighbor column
                    }
                }
            }
        }

        if (LayerConfig.logSecondPass() && cleaned > 0) {
            AronaLayersGen.LOGGER.info("[SecondPass] Chunk {},{}: tapered {} neighbor layer(s)",
                chunkPos.x, chunkPos.z, cleaned);
        }
    }

    public static boolean hasEnclosingCeiling(Chunk chunk, BlockPos pos, int maxHeight) {
        for (int dy = 1; dy <= maxHeight; dy++) {
            BlockPos checkPos = pos.up(dy);
            BlockState state = chunk.getBlockState(checkPos);

            if (state.isAir()) continue;
            // Replaceable blocks (tall grass, flowers, snow layers, water) are not ceilings.
            // Non-replaceable non-air blocks (stairs, slabs, glass, stone, fences, etc.) are.
            if (state.isReplaceable()) continue;

            return true;
        }
        return false;
    }

    public static boolean isInsideStructure(Chunk chunk, BlockPos pos) {
        List<BlockBox> bounds = structureBoundsLocal.get();
        if (bounds != null) {
            // Targeted debug: log all boxes that cover this XZ to diagnose missed detections
            if (pos.getX() == -139 && pos.getZ() == 55) {
                boolean anyXZ = false;
                for (BlockBox box : bounds) {
                    if (box.getMinX() <= -139 && -139 <= box.getMaxX()
                            && box.getMinZ() <= 55 && 55 <= box.getMaxZ()) {
                        anyXZ = true;
                        AronaLayersGen.LOGGER.info("[DebugBB] (-139,{},55) XZ-match: x=[{},{}] y=[{},{}] z=[{},{}]",
                            pos.getY(), box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY(), box.getMinZ(), box.getMaxZ());
                    }
                }
                if (!anyXZ) {
                    AronaLayersGen.LOGGER.info("[DebugBB] (-139,{},55) NO box covers this XZ. bounds.size={}", pos.getY(), bounds.size());
                }
            }
            int xzExpand = LayerConfig.STRUCTURE_INJECTION_EXTRA_BOUNDS ? LayerConfig.STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE : 0;
            for (BlockBox box : bounds) {
                if (box.contains(pos)) return true;
                // Also catch the beard-fill zone: terrain adaptation fills terrain up to
                // piece.minY - 1, so the layer target (pos) may fall 1-2 blocks below the
                // piece bottom but still within its XZ footprint.
                if (box.getMinX() <= pos.getX() && pos.getX() <= box.getMaxX()
                        && box.getMinZ() <= pos.getZ() && pos.getZ() <= box.getMaxZ()
                        && pos.getY() >= box.getMinY() - 2 && pos.getY() < box.getMinY()) {
                    return true;
                }
                // XZ buffer zone around the structure (does not change Y range)
                if (xzExpand > 0
                        && box.getMinX() - xzExpand <= pos.getX() && pos.getX() <= box.getMaxX() + xzExpand
                        && box.getMinZ() - xzExpand <= pos.getZ() && pos.getZ() <= box.getMaxZ() + xzExpand
                        && pos.getY() >= box.getMinY() - 2 && pos.getY() <= box.getMaxY()) {
                    return true;
                }
            }
            // Footprint check: XZ hull of each StructureStart, with Y range constraint.
            // Catches cleared ground, plazas and paths between piece boxes.
            if (LayerConfig.STRUCTURE_FOOTPRINT_CHECK) {
                List<BlockBox> footprints = structureFootprintLocal.get();
                if (footprints != null) {
                    for (BlockBox box : footprints) {
                        if (box.getMinX() <= pos.getX() && pos.getX() <= box.getMaxX()
                                && box.getMinZ() <= pos.getZ() && pos.getZ() <= box.getMaxZ()
                                && pos.getY() <= box.getMaxY() + LayerConfig.STRUCTURE_FOOTPRINT_ABOVE_MARGIN
                                && pos.getY() >= box.getMinY() - LayerConfig.STRUCTURE_FOOTPRINT_BELOW_MARGIN) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        try {
            int xzExpand = LayerConfig.STRUCTURE_INJECTION_EXTRA_BOUNDS ? LayerConfig.STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE : 0;
            for (StructureStart start : chunk.getStructureStarts().values()) {
                if (start == StructureStart.DEFAULT) continue;
                for (StructurePiece piece : start.getChildren()) {
                    BlockBox box = piece.getBoundingBox();
                    if (box.contains(pos)) return true;
                    if (box.getMinX() <= pos.getX() && pos.getX() <= box.getMaxX()
                            && box.getMinZ() <= pos.getZ() && pos.getZ() <= box.getMaxZ()
                            && pos.getY() >= box.getMinY() - 2 && pos.getY() < box.getMinY()) {
                        return true;
                    }
                    if (xzExpand > 0
                            && box.getMinX() - xzExpand <= pos.getX() && pos.getX() <= box.getMaxX() + xzExpand
                            && box.getMinZ() - xzExpand <= pos.getZ() && pos.getZ() <= box.getMaxZ() + xzExpand
                            && pos.getY() >= box.getMinY() - 2 && pos.getY() <= box.getMaxY()) {
                        return true;
                    }
                }
                // Footprint check in the fallback (no ThreadLocal) path
                if (LayerConfig.STRUCTURE_FOOTPRINT_CHECK && !start.getChildren().isEmpty()) {
                    BlockBox box = start.getBoundingBox();
                    if (box.getMinX() <= pos.getX() && pos.getX() <= box.getMaxX()
                            && box.getMinZ() <= pos.getZ() && pos.getZ() <= box.getMaxZ()
                            && pos.getY() <= box.getMaxY() + LayerConfig.STRUCTURE_FOOTPRINT_ABOVE_MARGIN
                            && pos.getY() >= box.getMinY() - LayerConfig.STRUCTURE_FOOTPRINT_BELOW_MARGIN) {
                        return true;
                    }
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
    public static final AtomicInteger debugSkipStructureElevated = new AtomicInteger();
    public static final AtomicInteger debugSkipConservativeSurface = new AtomicInteger();

    // ========== Structure Elevation Block Filter ==========

    private static Set<Block> elevationProcessBlocks = null;

    private static Set<Block> getElevationProcessBlocks() {
        if (elevationProcessBlocks == null) {
            elevationProcessBlocks = new java.util.HashSet<>();
            for (String id : io.arona74.aronalayersgen.ConfigLoader.loadBlockList("structure_elevation_blocks.json")) {
                net.minecraft.util.Identifier identifier = net.minecraft.util.Identifier.tryParse(id);
                if (identifier == null) {
                    AronaLayersGen.LOGGER.warn("[ElevationFilter] Invalid block ID: {}", id);
                    continue;
                }
                Block block = net.minecraft.registry.Registries.BLOCK.get(identifier);
                if (block == Blocks.AIR) {
                    AronaLayersGen.LOGGER.warn("[ElevationFilter] Block not found: {}", id);
                    continue;
                }
                elevationProcessBlocks.add(block);
            }
            AronaLayersGen.LOGGER.info("[ElevationFilter] Loaded {} blocks for structure elevation processing", elevationProcessBlocks.size());
        }
        return elevationProcessBlocks;
    }

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
    /**
     * Inject a layer with an RTF-derived natural surface base Y for structure detection.
     * rtfExpectedBaseY = floor(rtfHeight * worldHeight) — the noise-derived integer Y of
     * the natural terrain surface. Unaffected by block placement order or C2ME timing.
     * Use Integer.MIN_VALUE when RTF data is not available (falls back to snapshot).
     */
    public static boolean injectLayerAt(Chunk chunk, int worldX, int worldZ, int layerCount, boolean useSnowLayers, int rtfExpectedBaseY) {
        return injectLayerAtImpl(chunk, worldX, worldZ, layerCount, useSnowLayers, rtfExpectedBaseY);
    }

    /** Non-RTF callers: no expected base Y available, falls back to snapshot. */
    public static boolean injectLayerAt(Chunk chunk, int worldX, int worldZ, int layerCount, boolean useSnowLayers) {
        return injectLayerAtImpl(chunk, worldX, worldZ, layerCount, useSnowLayers, Integer.MIN_VALUE);
    }

    private static boolean injectLayerAtImpl(Chunk chunk, int worldX, int worldZ, int layerCount, boolean useSnowLayers, int rtfExpectedBaseY) {
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

        // Conservative surface heightmap: skip if the actual surface Y deviates from RTF's
        // noise-derived base Y by more than the configured tolerance. A deviation means a
        // structure (or other feature) has raised or lowered the terrain at this column.
        // Uses the raw heightmap value (surfaceY - 1) before any scan-down adjustments so
        // it reflects what is actually sitting at the top of the terrain right now.
        if (LayerConfig.CONSERVATIVE_SURFACE_HEIGHTMAP && rtfExpectedBaseY != Integer.MIN_VALUE) {
            int delta = (surfaceY - 1) - rtfExpectedBaseY;
            if (delta > LayerConfig.CONSERVATIVE_SURFACE_TOLERANCE_UP
                    || delta < -LayerConfig.CONSERVATIVE_SURFACE_TOLERANCE_DOWN) {
                if (LayerConfig.CONSERVATIVE_SURFACE_FALLBACK) {
                    int fallback = delta > 0
                        ? LayerConfig.CONSERVATIVE_SURFACE_FALLBACK_VALUE_UP
                        : LayerConfig.CONSERVATIVE_SURFACE_FALLBACK_VALUE_DOWN;
                    if (fallback <= 0) {
                        debugSkipConservativeSurface.incrementAndGet();
                        return false;
                    }
                    layerCount = fallback;
                } else {
                    debugSkipConservativeSurface.incrementAndGet();
                    return false;
                }
            }
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

        if (LayerConfig.logSnow() && surfaceIsIceOrWater) {
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
            if (LayerConfig.logSnow()) {
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
                if (LayerConfig.logSnow() && surfaceIsIceOrWater) {
                    AronaLayersGen.LOGGER.info("[IceTrace] ({},{}) fallback scan found nothing -> skip", worldX, worldZ);
                }
                debugSkipNoMapping.incrementAndGet();
                return false;
            }
            if (LayerConfig.logSnow() && surfaceIsIceOrWater) {
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

        // POST_FEATURES: detect surfaces elevated above original terrain by structures.
        //
        // Expected base Y determination (in priority order):
        // 1. RTF noise-derived base Y (rtfExpectedBaseY = floor(height * worldHeight)).
        //    Computed directly from RTF's terrain noise, independent of block placement
        //    order and C2ME parallelism. Always correct when RTF is active.
        // 2. Surface-phase snapshot (peekSurfaceY). Fallback for non-RTF callers.
        //    May be contaminated under C2ME if village FEATURES runs concurrently.
        //
        // Only skip/replace when terrain was ELEVATED (surfacePos.getY() > expectedBaseY).
        // Terrain that went DOWN (ravines from carvers, excavated village interiors) is NOT
        // skipped here — ravines are correct terrain, excavated interiors are caught by the
        // bounding-box check above.
        //
        // Powder snow is excluded: OCEAN_FLOOR_WG does not count it as solid.
        if (isPostFeaturesContext && LayerConfig.STRUCTURE_SKIP_ELEVATED && !surfaceIsPowderSnow) {
            // Resolve expectedBaseY: the Y of the top solid block under natural conditions.
            int expectedBaseY = Integer.MIN_VALUE;
            if (rtfExpectedBaseY != Integer.MIN_VALUE) {
                expectedBaseY = rtfExpectedBaseY; // RTF: floor(height * worldHeight)
            } else {
                int preY = PreStructureHeightmapStorage.peekSurfaceY(chunk, localX, localZ);
                if (preY > chunk.getBottomY()) {
                    expectedBaseY = preY - 1; // snapshot is exclusive; convert to inclusive base Y
                }
            }
            if (worldX == -125 && worldZ == 74) {
                AronaLayersGen.LOGGER.info("[DebugPos] (-125,74) rtfBase={} expectedBaseY={} surfacePos.Y={} surfaceBlock={} layerCount={} isPostFeaturesCtx={} SKIP_ELEVATED={}",
                    rtfExpectedBaseY, expectedBaseY, surfacePos.getY(),
                    net.minecraft.registry.Registries.BLOCK.getId(surfaceBlock),
                    layerCount, isPostFeaturesContext, LayerConfig.STRUCTURE_SKIP_ELEVATED);
            }
            // Surface above expectedBaseY means structure elevated the terrain.
            if (expectedBaseY != Integer.MIN_VALUE && surfacePos.getY() > expectedBaseY) {
                // Only process blocks listed in structure_elevation_blocks.json.
                if (!getElevationProcessBlocks().contains(surfaceBlock)) {
                    // Block not in filter list — leave it untouched, fall through to normal injection.
                } else {
                boolean exactlyOneBlock = surfacePos.getY() == expectedBaseY + 1;
                // Only act if the space above is clear (no building floor/wall above the surface).
                // Replaceable blocks (grass, flowers, ferns, etc.) count as clear since they are
                // vegetation, not structural blocks, and won't float after the elevated block is removed.
                BlockState spaceAbove = chunk.getBlockState(surfacePos.up());
                boolean spaceAboveClear = spaceAbove.isAir()
                        || spaceAbove.getBlock() == Blocks.WATER
                        || spaceAbove.isReplaceable();
                if (!spaceAboveClear) {
                    if (LayerConfig.logSkipElevated()) {
                        AronaLayersGen.LOGGER.info("[SkipElevated] ({},{}) blocked: non-air above Y={} is {}",
                            worldX, worldZ, surfacePos.getY() + 1,
                            net.minecraft.registry.Registries.BLOCK.getId(spaceAbove.getBlock()));
                    }
                    debugSkipStructureElevated.incrementAndGet();
                    return false;
                }
                if (LayerConfig.STRUCTURE_REPLACE_ELEVATED && exactlyOneBlock) {
                    // Exactly 1-block elevation: handle based on RTF layer count.
                    if (layerCount == 0) {
                        // RTF terrain is at an integer height (fractional depth ≈ 0).
                        // No layer belongs here, so remove the village-raised block by
                        // placing AIR. This exposes the natural surface underneath so
                        // plants placed afterward land at the correct Y, not one too high.
                        if (LayerConfig.logSkipElevated()) {
                            AronaLayersGen.LOGGER.info("[SkipElevated] ({},{}) REMOVE raised block Y={} expectedBase={} block={} (layerCount=0)",
                                worldX, worldZ, surfacePos.getY(), expectedBaseY,
                                net.minecraft.registry.Registries.BLOCK.getId(surfaceBlock));
                        }
                        setBlockStateSafe(chunk, surfacePos, Blocks.AIR.getDefaultState());
                        clearVegetationAbove(chunk, worldX, worldZ, surfacePos.getY() + 1);
                        if (LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP) cleanupNearbyLayers(chunk, worldX, worldZ, surfacePos.getY());
                        debugSkipStructureElevated.incrementAndGet();
                        return false;
                    }
                    // layerCount > 0: replace the raised surface block with the layer in-place.
                    Block layerBlock = useSnowLayers ? Blocks.SNOW : getMappingRegistry().getLayerBlock(surfaceBlock, layerCount);
                    if (layerBlock != null) {
                        if (layerBlock == AronaLayersGen.POWDER_SNOW_LAYER && layerCount >= 8) {
                            layerBlock = Blocks.POWDER_SNOW;
                        }
                        BlockState layerState = layerBlock.getDefaultState();
                        layerState = applyLayerCount(layerState, layerBlock, layerCount);
                        if (LayerConfig.logSkipElevated()) {
                            AronaLayersGen.LOGGER.info("[SkipElevated] ({},{}) REPLACE surface Y={} expectedBase={} block={} -> {} layers={}",
                                worldX, worldZ, surfacePos.getY(), expectedBaseY,
                                net.minecraft.registry.Registries.BLOCK.getId(surfaceBlock),
                                net.minecraft.registry.Registries.BLOCK.getId(layerBlock), layerCount);
                        }
                        setBlockStateSafe(chunk, surfacePos, layerState);
                        if (LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP) cleanupNearbyLayers(chunk, worldX, worldZ, surfacePos.getY());
                        return true;
                    }
                }
                // Elevation > 1 block: optionally level the mound if REPLACE_ELEVATED_HIGH is
                // enabled and the delta is within the configured maximum.
                //
                // Algorithm: clear all blocks from surfacePos down to expectedBaseY+2 (the extra
                // fill above the first elevated position), then at expectedBaseY+1 either place
                // a layer (layerCount>0) or AIR (layerCount=0). The layer mapping uses the natural
                // surface block at expectedBaseY rather than the elevated surface block.
                //
                // Example delta=2: surfacePos=Y=75, expectedBase=73
                //   clear Y=75 → AIR
                //   at Y=74: place layer using block at Y=73
                //   result: natural at Y=73, layer at Y=74, air at Y=75
                int delta = surfacePos.getY() - expectedBaseY;
                if (LayerConfig.STRUCTURE_REPLACE_ELEVATED_HIGH && delta <= LayerConfig.STRUCTURE_REPLACE_ELEVATED_HIGH_MAX) {
                    // Clear elevated blocks above the first elevated position.
                    for (int y = surfacePos.getY(); y > expectedBaseY + 1; y--) {
                        setBlockStateSafe(chunk, new BlockPos(worldX, y, worldZ), Blocks.AIR.getDefaultState());
                    }
                    // Clear any vegetation that was sitting on top of the now-removed elevated blocks.
                    clearVegetationAbove(chunk, worldX, worldZ, surfacePos.getY() + 1);
                    BlockPos layerTargetPos = new BlockPos(worldX, expectedBaseY + 1, worldZ);
                    if (layerCount == 0) {
                        // No layer at natural terrain either — clear the remaining elevated block too.
                        setBlockStateSafe(chunk, layerTargetPos, Blocks.AIR.getDefaultState());
                        if (LayerConfig.logSkipElevated()) {
                            AronaLayersGen.LOGGER.info("[SkipElevated] ({},{}) REMOVE-HIGH Y={}-{} expectedBase={} delta={} (layerCount=0)",
                                worldX, worldZ, expectedBaseY + 1, surfacePos.getY(), expectedBaseY, delta);
                        }
                        if (LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP) cleanupNearbyLayers(chunk, worldX, worldZ, surfacePos.getY());
                        debugSkipStructureElevated.incrementAndGet();
                        return false;
                    }
                    // Use the natural surface block for the layer mapping.
                    Block naturalBlock = chunk.getBlockState(new BlockPos(worldX, expectedBaseY, worldZ)).getBlock();
                    Block layerBlock = useSnowLayers ? Blocks.SNOW : getMappingRegistry().getLayerBlock(naturalBlock, layerCount);
                    if (layerBlock != null) {
                        if (layerBlock == AronaLayersGen.POWDER_SNOW_LAYER && layerCount >= 8) {
                            layerBlock = Blocks.POWDER_SNOW;
                        }
                        BlockState layerState = layerBlock.getDefaultState();
                        layerState = applyLayerCount(layerState, layerBlock, layerCount);
                        if (LayerConfig.logSkipElevated()) {
                            AronaLayersGen.LOGGER.info("[SkipElevated] ({},{}) LEVEL-HIGH cleared Y={}-{} layer at Y={} naturalBlock={} -> {} layers={}",
                                worldX, worldZ, expectedBaseY + 2, surfacePos.getY(),
                                expectedBaseY + 1,
                                net.minecraft.registry.Registries.BLOCK.getId(naturalBlock),
                                net.minecraft.registry.Registries.BLOCK.getId(layerBlock), layerCount);
                        }
                        setBlockStateSafe(chunk, layerTargetPos, layerState);
                        if (LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP) cleanupNearbyLayers(chunk, worldX, worldZ, surfacePos.getY());
                        return true;
                    }
                }
                if (LayerConfig.logSkipElevated()) {
                    AronaLayersGen.LOGGER.info("[SkipElevated] ({},{}) SKIP Y={} expectedBase={} delta={} block={}",
                        worldX, worldZ, surfacePos.getY(), expectedBaseY, delta,
                        net.minecraft.registry.Registries.BLOCK.getId(surfaceBlock));
                }
                if (LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP) cleanupNearbyLayers(chunk, worldX, worldZ, surfacePos.getY());
                debugSkipStructureElevated.incrementAndGet();
                return false;
                } // end elevation filter else
            }
        }

        boolean layerPlaced = false;

        if (layerCount <= 0) {
            debugSkipLayerZero.incrementAndGet();
        }

        BlockPos abovePos = surfacePos.up();

        if (LayerConfig.STRUCTURE_INJECTION && isInsideStructure(chunk, abovePos)) {
            addSecondPassTarget(abovePos.getX(), abovePos.getZ(), abovePos.getY(), surfaceBlock);
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
                boolean seagrassAtSurface = false;
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
                            BlockPos waterCheckPos = replacedTallPlant ? abovePos.up().up() : abovePos.up();
                            if (chunk.getBlockState(waterCheckPos).getBlock() == Blocks.WATER) {
                                // Fully submerged: convert to CR equivalent above the layer
                                replacedPlant = existingState.getBlock();
                            } else {
                                // Surface seagrass: no room for CR equivalent above.
                                // Remove the seagrass and place a waterlogged layer in its place.
                                seagrassAtSurface = true;
                            }
                            if (replacedTallPlant) {
                                // Upper half was occupying a water column block; restore water there.
                                BlockState upperFill = seagrassAtSurface
                                    ? Blocks.WATER.getDefaultState()
                                    : Blocks.AIR.getDefaultState();
                                setBlockStateSafe(chunk, abovePos.up(), upperFill);
                            }
                        } else {
                            replacedPlant = existingState.getBlock();
                            if (replacedTallPlant) {
                                setBlockStateSafe(chunk, abovePos.up(), Blocks.AIR.getDefaultState());
                            }
                        }
                    } else if (canShiftPlant) {
                        replacedTallPlant = isTallPlant(existingState);
                        if (isSeagrass) {
                            BlockPos waterCheckPos = replacedTallPlant ? abovePos.up().up() : abovePos.up();
                            if (chunk.getBlockState(waterCheckPos).getBlock() == Blocks.WATER) {
                                // Fully submerged: shift the seagrass up onto the layer
                                replacedPlantState = existingState;
                            } else {
                                // Surface seagrass: no room to shift into. Place waterlogged layer only.
                                seagrassAtSurface = true;
                            }
                            if (replacedTallPlant) {
                                savedTallUpperState = chunk.getBlockState(abovePos.up());
                                // Upper half was occupying a water column block; restore water there.
                                BlockState upperFill = seagrassAtSurface
                                    ? Blocks.WATER.getDefaultState()
                                    : Blocks.AIR.getDefaultState();
                                setBlockStateSafe(chunk, abovePos.up(), upperFill);
                            }
                        } else {
                            replacedPlantState = existingState;
                            if (replacedTallPlant) {
                                savedTallUpperState = chunk.getBlockState(abovePos.up());
                                setBlockStateSafe(chunk, abovePos.up(), Blocks.AIR.getDefaultState());
                            }
                        }
                    } else {
                        debugSkipNotAir.incrementAndGet();
                        return false;
                    }
                }

                BlockState layerState = layerBlock.getDefaultState();
                layerState = applyLayerCount(layerState, layerBlock, layerCount);

                // Seagrass replacement (CR or VP): waterlog the layer whenever seagrass was here
                Block seagrassCheck = replacedPlantState != null ? replacedPlantState.getBlock() : replacedPlant;
                if ((seagrassAtSurface
                        || (seagrassCheck != null && (seagrassCheck == Blocks.SEAGRASS || seagrassCheck == Blocks.TALL_SEAGRASS)))
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
                    if (LayerConfig.logSnow()) {
                        AronaLayersGen.LOGGER.info("[IceTrace] ({},{}) placing underwater layer {} at Y={} (waterlogged={})",
                            worldX, worldZ,
                            net.minecraft.registry.Registries.BLOCK.getId(layerState.getBlock()),
                            abovePos.getY(),
                            layerState.contains(Properties.WATERLOGGED) && layerState.get(Properties.WATERLOGGED));
                    }
                }

                if (LayerConfig.PLACE_WET_SAND) {
                    Block wetSub = getWetSandSubstitute(layerState.getBlock());
                    if (wetSub != null) {
                        boolean isWet = abovePos.getY() < 63
                            || (layerState.contains(Properties.WATERLOGGED) && layerState.get(Properties.WATERLOGGED));
                        if (isWet) {
                            BlockState wetState = applyLayerCount(wetSub.getDefaultState(), wetSub, layerCount);
                            if (layerState.contains(Properties.WATERLOGGED) && layerState.get(Properties.WATERLOGGED)
                                    && wetState.contains(Properties.WATERLOGGED)) {
                                wetState = wetState.with(Properties.WATERLOGGED, true);
                            }
                            layerState = wetState;
                        }
                    }
                }

                setBlockStateSafe(chunk, abovePos, layerState);
                layerPlaced = true;

                if (LayerConfig.REPLACE_DIRT_PATH && surfaceBlock == Blocks.DIRT_PATH) {
                    Block fullBlock = getFullBlock(layerBlock);
                    if (fullBlock != null) {
                        setBlockStateSafe(chunk, surfacePos, fullBlock.getDefaultState());
                        if (LayerConfig.logFoliage()) {
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
                                // CR equivalent is also a tall plant — place lower + upper halves
                                conquestState = conquestState.with(Properties.DOUBLE_BLOCK_HALF,
                                    net.minecraft.block.enums.DoubleBlockHalf.LOWER);
                                setBlockStateSafe(chunk, plantPos, conquestState);

                                BlockPos upperPos = plantPos.up();
                                BlockState upperState = conquestPlant.getDefaultState();
                                upperState = applyLayerCount(upperState, conquestPlant, layerCount);
                                upperState = upperState.with(Properties.DOUBLE_BLOCK_HALF,
                                    net.minecraft.block.enums.DoubleBlockHalf.UPPER);
                                setBlockStateSafe(chunk, upperPos, upperState);
                            } else {
                                // CR equivalent is a single-block plant — only place at lower position
                                setBlockStateSafe(chunk, plantPos, conquestState);
                            }
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

        boolean enhancedRockHandled = false;
        if (replacedPlant == null && LayerConfig.CONQUEST_ENHANCED_ROCKS) {
            enhancedRockHandled = tryPlaceEnhancedRock(chunk, decorPos, surfaceBlock, worldX, worldZ, layerPlaced ? abovePos : null);
        }

        if (replacedPlant == null && LayerConfig.PLACE_ROCKS && !enhancedRockHandled) {
            tryPlaceRock(chunk, decorPos, surfaceBlock, worldX, worldZ, layerPlaced ? abovePos : null);
        }

        if (replacedPlant == null && LayerConfig.CONQUEST_ENHANCED_EXTRA_FOLIAGE) {
            tryPlaceEnhancedFoliage(chunk, decorPos, surfaceBlock, worldX, worldZ, layerPlaced ? abovePos : null);
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
    /**
     * Remove any layer blocks within the configured Chebyshev radius of (worldX, worldZ)
     * at Y levels near centerY. Only operates within the current chunk.
     * Called after detecting an elevated position to clean up surrounding layers.
     */
    private static void cleanupNearbyLayers(Chunk chunk, int worldX, int worldZ, int centerY) {
        int radius = LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE;
        int chunkMinX = chunk.getPos().getStartX();
        int chunkMinZ = chunk.getPos().getStartZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = worldX + dx, nz = worldZ + dz;
                if (nx < chunkMinX || nx >= chunkMinX + 16
                        || nz < chunkMinZ || nz >= chunkMinZ + 16) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    int ny = centerY + dy;
                    if (ny < chunk.getBottomY() || ny >= chunk.getTopY()) continue;
                    BlockPos pos = new BlockPos(nx, ny, nz);
                    BlockState state = chunk.getBlockState(pos);
                    boolean isLayer = state.contains(Properties.LAYERS)
                            || getCRLayerProperty(state.getBlock()) != null;
                    if (isLayer) {
                        setBlockStateSafe(chunk, pos, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }
    }

    /**
     * Clear any replaceable vegetation (grass, flowers, ferns, etc.) sitting directly
     * above the given Y position. Continues upward until air or a non-replaceable block.
     * Called after removing an elevated block so stranded plants don't float.
     */
    private static void clearVegetationAbove(Chunk chunk, int worldX, int worldZ, int aboveY) {
        for (int y = aboveY; y < chunk.getTopY(); y++) {
            BlockState state = chunk.getBlockState(new BlockPos(worldX, y, worldZ));
            if (state.isAir()) break;
            if (!state.isReplaceable()) break;
            setBlockStateSafe(chunk, new BlockPos(worldX, y, worldZ), Blocks.AIR.getDefaultState());
        }
    }

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
            chunk.setBlockState(pos, state, false);
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
                    if (!aboveState.isReplaceable()) {
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

        if ((corrected > 0 || removed > 0 || waterlogged > 0 || structureCleanup > 0) && LayerConfig.logCorrection()) {
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
                    addSecondPassTarget(worldX, worldZ, pos.getY(), chunk.getBlockState(pos.down()).getBlock());
                    removed++;
                    break;
                }
            }
        }

        if ((removed > 0 || preserved > 0) && LayerConfig.logStructureNoLayers()) {
            AronaLayersGen.LOGGER.info("[StructureNoLayers] Chunk {},{}: removed={}, preserved={} (had plants above)",
                chunk.getPos().x, chunk.getPos().z, removed, preserved);
        }
    }
}
