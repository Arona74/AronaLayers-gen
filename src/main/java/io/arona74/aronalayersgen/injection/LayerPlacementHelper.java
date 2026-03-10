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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final HashMap<Block, IntProperty> crLayerPropertyCache = new HashMap<>();

    /**
     * Find CR's custom "layer" IntProperty on a block (values 1-4).
     * Returns null if the block doesn't have it (e.g. VP blocks use vanilla LAYERS).
     */
    public static IntProperty getCRLayerProperty(Block block) {
        if (crLayerPropertyCache.containsKey(block)) {
            return crLayerPropertyCache.get(block);
        }
        IntProperty result = null;
        for (Property<?> prop : block.getStateManager().getProperties()) {
            if (prop.getName().equals("layer") && prop instanceof IntProperty) {
                result = (IntProperty) prop;
                break;
            }
        }
        crLayerPropertyCache.put(block, result);
        return result;
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
    private static final HashMap<Block, Block> fullBlockCache = new HashMap<>();

    /**
     * Derive the full-block equivalent of a layer/slab block by removing
     * common suffixes (_slab, _layer) from the block ID.
     */
    public static Block getFullBlock(Block layerBlock) {
        if (fullBlockCache.containsKey(layerBlock)) {
            return fullBlockCache.get(layerBlock);
        }

        net.minecraft.util.Identifier layerId = net.minecraft.registry.Registries.BLOCK.getId(layerBlock);
        String path = layerId.getPath();
        String namespace = layerId.getNamespace();

        String[] suffixes = {"_slab", "_layer"};
        for (String suffix : suffixes) {
            if (path.endsWith(suffix)) {
                String fullPath = path.substring(0, path.length() - suffix.length());
                net.minecraft.util.Identifier fullId = new net.minecraft.util.Identifier(namespace, fullPath);
                Block fullBlock = net.minecraft.registry.Registries.BLOCK.get(fullId);
                if (fullBlock != Blocks.AIR) {
                    fullBlockCache.put(layerBlock, fullBlock);
                    return fullBlock;
                }
            }
        }

        fullBlockCache.put(layerBlock, null);
        return null;
    }

    // Cache for density property lookup per block
    private static final HashMap<Block, IntProperty> densityPropertyCache = new HashMap<>();

    /**
     * Find the "density" IntProperty on a block (used by CR rock blocks).
     * Returns null if the block doesn't have it.
     */
    public static IntProperty getDensityProperty(Block block) {
        if (densityPropertyCache.containsKey(block)) {
            return densityPropertyCache.get(block);
        }
        IntProperty result = null;
        for (Property<?> prop : block.getStateManager().getProperties()) {
            if (prop.getName().equals("density") && prop instanceof IntProperty) {
                result = (IntProperty) prop;
                break;
            }
        }
        densityPropertyCache.put(block, result);
        return result;
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

        chunk.setBlockState(rockPos, rockState, false);

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

        chunk.setBlockState(foliagePos, foliageState, false);

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
            for (StructureStart start : chunk.getStructureStarts().values()) {
                if (start == StructureStart.DEFAULT) continue;
                for (StructurePiece piece : start.getChildren()) {
                    bounds.add(piece.getBoundingBox());
                }
            }

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
        } catch (Exception e) {
            AronaLayersGen.LOGGER.debug("[Structure] Error collecting structure bounds: {}", e.getMessage());
        }

        if (LayerConfig.DEBUG_LOGGING && !bounds.isEmpty()) {
            AronaLayersGen.LOGGER.info("[Structure] Chunk {},{}: found {} structure piece bounds",
                chunk.getPos().x, chunk.getPos().z, bounds.size());
        }

        structureBoundsLocal.set(bounds.isEmpty() ? null : bounds);
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

    public static int debugSkipSnowy = 0;
    public static int debugSkipNoSurface = 0;
    public static int debugSkipNoMapping = 0;
    public static int debugSkipLayerZero = 0;
    public static int debugSkipNoLayerBlock = 0;
    public static int debugSkipNotAir = 0;
    public static int debugSkipEnclosed = 0;

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
            debugSkipNoSurface++;
            return false;
        }

        boolean isSnowyBiome = false;
        {
            RegistryEntry<Biome> biome = chunk.getBiomeForNoiseGen(localX >> 2, surfaceY >> 2, localZ >> 2);
            BlockPos biomePos = new BlockPos(worldX, surfaceY - 1, worldZ);
            isSnowyBiome = biome.value().isCold(biomePos);
        }

        if (LayerConfig.SKIP_SNOWY_BIOMES && isSnowyBiome) {
            debugSkipSnowy++;
            return false;
        }

        if (isSnowyBiome && LayerConfig.IMPROVE_SNOWY_BIOMES) {
            useSnowLayers = true;
        }

        BlockPos surfacePos = new BlockPos(worldX, surfaceY - 1, worldZ);
        BlockState surfaceState = chunk.getBlockState(surfacePos);
        Block surfaceBlock = surfaceState.getBlock();

        if (useSnowLayers && surfaceBlock == Blocks.SNOW_BLOCK) {
            chunk.setBlockState(surfacePos, Blocks.SNOW.getDefaultState().with(Properties.LAYERS, 8), false);
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
                debugSkipNoMapping++;
                return false;
            }
        }

        if (!getMappingRegistry().hasMapping(surfaceBlock)) {
            debugSkipNoMapping++;
            return false;
        }

        boolean layerPlaced = false;

        if (layerCount <= 0) {
            debugSkipLayerZero++;
        }

        BlockPos abovePos = surfacePos.up();

        if (LayerConfig.STRUCTURE_INJECTION && isInsideStructure(chunk, abovePos)) {
            return false;
        }

        if (LayerConfig.STRUCTURE_INJECTION && LayerConfig.ENCLOSED_SPACE_CHECK) {
            if (hasEnclosingCeiling(chunk, abovePos, LayerConfig.ENCLOSED_SPACE_HEIGHT)) {
                debugSkipEnclosed++;
                return false;
            }
        }

        BlockState existingState = chunk.getBlockState(abovePos);
        boolean underwater = existingState.getBlock() == Blocks.WATER;

        Block replacedPlant = null;
        BlockState replacedPlantState = null;
        if (layerCount > 0) {
            Block layerBlock = useSnowLayers ? Blocks.SNOW : getMappingRegistry().getLayerBlock(surfaceBlock, layerCount);
            if (layerBlock == null) {
                debugSkipNoLayerBlock++;
            } else {
                boolean replacedTallPlant = false;
                BlockState savedTallUpperState = null;
                if (!existingState.isAir() && !underwater) {
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
                        replacedPlant = existingState.getBlock();
                        replacedTallPlant = isTallPlant(existingState);
                        if (replacedTallPlant) {
                            chunk.setBlockState(abovePos.up(), Blocks.AIR.getDefaultState(), false);
                        }
                    } else if (canShiftPlant) {
                        replacedTallPlant = isTallPlant(existingState);
                        if (isSeagrass) {
                            // Seagrass needs water above to shift into; bail if at the water surface edge
                            BlockPos waterCheckPos = replacedTallPlant ? abovePos.up().up() : abovePos.up();
                            if (chunk.getBlockState(waterCheckPos).getBlock() != Blocks.WATER) {
                                debugSkipNotAir++;
                                return false;
                            }
                        }
                        replacedPlantState = existingState;
                        if (replacedTallPlant) {
                            savedTallUpperState = chunk.getBlockState(abovePos.up());
                            chunk.setBlockState(abovePos.up(), Blocks.AIR.getDefaultState(), false);
                        }
                    } else {
                        debugSkipNotAir++;
                        return false;
                    }
                }

                BlockState layerState = layerBlock.getDefaultState();
                layerState = applyLayerCount(layerState, layerBlock, layerCount);

                // VP seagrass shift: water above was verified in detection; waterlog the layer
                if (replacedPlantState != null) {
                    Block shiftedBlock = replacedPlantState.getBlock();
                    if ((shiftedBlock == Blocks.SEAGRASS || shiftedBlock == Blocks.TALL_SEAGRASS)
                            && layerState.contains(Properties.WATERLOGGED)) {
                        layerState = layerState.with(Properties.WATERLOGGED, true);
                    }
                }

                if (underwater) {
                    if (layerState.contains(Properties.WATERLOGGED)) {
                        layerState = layerState.with(Properties.WATERLOGGED, true);
                    } else {
                        debugSkipNotAir++;
                        return false;
                    }
                }

                chunk.setBlockState(abovePos, layerState, false);
                layerPlaced = true;

                if (LayerConfig.REPLACE_DIRT_PATH && surfaceBlock == Blocks.DIRT_PATH) {
                    Block fullBlock = getFullBlock(layerBlock);
                    if (fullBlock != null) {
                        chunk.setBlockState(surfacePos, fullBlock.getDefaultState(), false);
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
                        chunk.setBlockState(plantPos, replacedPlantState, false);
                        if (replacedTallPlant && savedTallUpperState != null) {
                            chunk.setBlockState(plantPos.up(), savedTallUpperState, false);
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
                            chunk.setBlockState(plantPos, conquestState, false);

                            BlockPos upperPos = plantPos.up();
                            BlockState upperState = conquestPlant.getDefaultState();
                            upperState = applyLayerCount(upperState, conquestPlant, layerCount);
                            if (upperState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                                upperState = upperState.with(Properties.DOUBLE_BLOCK_HALF,
                                    net.minecraft.block.enums.DoubleBlockHalf.UPPER);
                            }
                            chunk.setBlockState(upperPos, upperState, false);
                        } else {
                            chunk.setBlockState(plantPos, conquestState, false);
                        }
                    }
                }
            }
        } else {
            if (!existingState.isAir() && !underwater) {
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
                    chunk.setBlockState(layerPos, Blocks.AIR.getDefaultState(), false);
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
                        chunk.setBlockState(layerPos, Blocks.AIR.getDefaultState(), false);
                        structureCleanup++;
                        continue;
                    }
                }

                boolean waterAbove = isWaterAt(chunk, layerPos.up());
                if (waterAbove && !LayerConfig.UNDERWATER_LAYERS) {
                    // Layer was placed underwater (e.g. during CARVERS before water filled in)
                    // but underwater layers are disabled; remove it
                    chunk.setBlockState(layerPos, Blocks.AIR.getDefaultState(), false);
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
                        chunk.setBlockState(layerPos, newState, false);
                        corrected++;
                        changed = true;
                    }
                } else {
                    chunk.setBlockState(layerPos,
                        shouldBeWaterlogged ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState(),
                        false);
                    removed++;
                    changed = true;
                }

                if (!changed && shouldBeWaterlogged && layerState.contains(Properties.WATERLOGGED)
                        && !layerState.get(Properties.WATERLOGGED)) {
                    chunk.setBlockState(layerPos,
                        layerState.with(Properties.WATERLOGGED, true), false);
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

                    chunk.setBlockState(pos, Blocks.AIR.getDefaultState(), false);
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
