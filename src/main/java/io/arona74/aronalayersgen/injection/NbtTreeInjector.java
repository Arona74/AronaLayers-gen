package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Ids;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.NbtTreeRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles CR NBT tree placement in two modes:
 *
 * 1. Direct intercept (non-RTF worlds): tryPlaceTree() is called from
 *    NbtTreeFeatureMixin when vanilla TreeFeature.generate() is invoked.
 *
 * 2. Scan-and-replace (RTF worlds): RTF bypasses vanilla TreeFeature entirely.
 *    ServerChunkEvents.CHUNK_LOAD fires after the chunk is fully in the world
 *    cache; scanAndReplace() scans for vanilla log trunk-bases (identified by
 *    the AXIS property and "minecraft" namespace), clears the whole tree, and
 *    places a CR NBT tree in its place.
 */
public class NbtTreeInjector {

    // Per-file caches populated on first load
    private static final Map<Path, StructureTemplate> TEMPLATE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Path, BlockPos>          ANCHOR_CACHE   = new ConcurrentHashMap<>();

    // Set by SaplingBlockGrowMixin before vanilla removes the sapling, cleared after.
    // Lets tryPlaceTree know which sapling triggered growth even though the block is gone.
    private static final ThreadLocal<String> CURRENT_SAPLING_GROW = new ThreadLocal<>();

    public static void setSaplingGrowContext(String saplingId) { CURRENT_SAPLING_GROW.set(saplingId); }
    public static void clearSaplingGrowContext()                    { CURRENT_SAPLING_GROW.remove(); }

    private static final Rotation[] ROTATIONS = Rotation.values();

    // Worldgen deferred trees: chunk key → list of tree origin positions.
    // Positions are recorded during worldgen (worker threads) when vanilla tree
    // generation is cancelled, then placed on CHUNK_LOAD (server thread) where
    // the full ServerLevel is available and chunk boundaries are not an issue.
    private static final ConcurrentHashMap<Long, List<BlockPos>> PENDING_TREES = new ConcurrentHashMap<>();

    /**
     * Returns true if the worldgen mixin should handle (cancel + queue) this tree position.
     * When vanilla_fallback=false: always true — always cancel vanilla.
     * When vanilla_fallback=true: only true if the biome is configured in the registry,
     * so that unconfigured biomes let vanilla/RTF tree generation run unimpeded.
     */
    public static boolean willHandleWorldgenTree(WorldGenLevel world, BlockPos pos) {
        if (!LayerConfig.CR_NBT_TREES_VANILLA_FALLBACK) return true;
        Optional<ResourceKey<Biome>> biomeKey = world.getBiome(pos).unwrapKey();
        if (biomeKey.isEmpty()) return false;
        return NbtTreeRegistry.getInstance().hasBiome(Compat.keyId(biomeKey.get()));
    }

    /** Called from the worldgen mixin to cancel vanilla and queue for deferred placement. */
    public static void queueWorldgenTree(BlockPos origin) {
        long key = Compat.chunkPosAsLong(origin.getX() >> 4, origin.getZ() >> 4);
        PENDING_TREES.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(origin);
    }

    // Chunks whose deferred trees are ready to place, waiting for the next server tick.
    // Populated on CHUNK_LOAD (no heavy work), drained on END_SERVER_TICK (safe world access).
    private static final ConcurrentHashMap<Long, ServerLevel> READY_CHUNKS = new ConcurrentHashMap<>();

    /** Called from CHUNK_LOAD: records that this chunk's deferred trees can be placed soon. */
    public static void onChunkLoad(ServerLevel world, LevelChunk chunk) {
        if (!LayerConfig.CR_NBT_TREES) return;
        long key = Compat.chunkPosToLong(chunk.getPos());
        if (PENDING_TREES.containsKey(key)) {
            READY_CHUNKS.put(key, world);
        }
    }

    /** Called from END_SERVER_TICK: places deferred trees for all ready chunks. */
    public static void flushReadyChunks() {
        if (READY_CHUNKS.isEmpty()) return;
        List<Map.Entry<Long, ServerLevel>> batch = new ArrayList<>(READY_CHUNKS.entrySet());
        for (Map.Entry<Long, ServerLevel> e : batch) {
            READY_CHUNKS.remove(e.getKey());
            placeDeferredTrees(e.getValue(), e.getKey());
        }
    }

    private static void placeDeferredTrees(ServerLevel world, long chunkKey) {
        List<BlockPos> positions = PENDING_TREES.remove(chunkKey);
        if (positions == null || positions.isEmpty()) return;

        RandomSource rand = RandomSource.create(world.getSeed() ^ chunkKey ^ 0x4E42547265654C47L);
        for (BlockPos pos : positions) {
            if (LayerConfig.logNbtTrees())
                AronaLayersGen.LOGGER.info("[NbtTrees] worldgen-deferred pos={}", pos);
            tryPlaceTree(world, pos, rand);
        }
    }

    // -------------------------------------------------------------------------
    // Mode 1: direct intercept (called from NbtTreeFeatureMixin)
    // -------------------------------------------------------------------------

    /**
     * Try to place a CR NBT tree at the given position.
     * Called at HEAD of TreeFeature.generate() — vanilla tree hasn't been placed yet.
     * Returns true if a CR tree was placed (caller should cancel vanilla).
     */
    public static boolean tryPlaceTree(WorldGenLevel world, BlockPos surfacePos, RandomSource random) {
        NbtTreeRegistry registry = NbtTreeRegistry.getInstance();

        // Sapling-based selection: SaplingBlockGrowMixin sets CURRENT_SAPLING_GROW before vanilla
        // removes the block, so we can still identify the species even though the block is gone.
        String saplingId = CURRENT_SAPLING_GROW.get();
        Optional<Path> variantOpt = Optional.empty();

        if (saplingId != null) {
            variantOpt = registry.selectVariantForSapling(saplingId, random);
            if (variantOpt.isPresent()) {
                if (LayerConfig.logNbtTrees())
                    AronaLayersGen.LOGGER.info("[NbtTrees] sapling-based sapling={} pos={}", saplingId, surfacePos);
            } else if (LayerConfig.CR_NBT_TREES_VANILLA_FALLBACK) {
                // No entry for this sapling type — let vanilla grow it
                if (LayerConfig.logNbtTrees())
                    AronaLayersGen.LOGGER.info("[NbtTrees] SKIP unconfigured-sapling sapling={} pos={}", saplingId, surfacePos);
                return false;
            }
            // else: fallback disabled, fall through to biome-based
        }

        if (variantOpt.isEmpty()) {
            // Biome-based fallback (worldgen deferred, or sapling with fallback disabled)
            Optional<ResourceKey<Biome>> biomeKeyOpt = world.getBiome(surfacePos).unwrapKey();
            if (biomeKeyOpt.isEmpty()) {
                if (LayerConfig.logNbtTrees()) AronaLayersGen.LOGGER.info("[NbtTrees] SKIP no-biome-key pos={}", surfacePos);
                return false;
            }
            String biomeId = Compat.keyId(biomeKeyOpt.get());

            if (!registry.hasBiome(biomeId)) {
                if (LayerConfig.logNbtTrees()) AronaLayersGen.LOGGER.info("[NbtTrees] SKIP biome-not-configured biome={} pos={}", biomeId, surfacePos);
                return false;
            }
            float chance = registry.getChance(biomeId);
            float roll = random.nextFloat();
            if (roll >= chance) {
                if (LayerConfig.logNbtTrees()) AronaLayersGen.LOGGER.info("[NbtTrees] SKIP chance={} roll={} biome={} pos={}", chance, roll, biomeId, surfacePos);
                return false;
            }

            // Surface block for species selection: if mod block (e.g. CR layer), look one level deeper.
            BlockState belowState = world.getBlockState(surfacePos.below());
            if (belowState.isAir() || !belowState.getFluidState().isEmpty()) {
                if (LayerConfig.logNbtTrees()) AronaLayersGen.LOGGER.info("[NbtTrees] SKIP invalid-surface biome={} pos={}", biomeId, surfacePos);
                return false;
            }
            Block surfaceBlock = belowState.getBlock();
            if (!"minecraft".equals(Ids.namespace(Compat.blockId(surfaceBlock)))) {
                BlockState deeper = world.getBlockState(surfacePos.below().below());
                if (!deeper.isAir() && deeper.getFluidState().isEmpty()) {
                    surfaceBlock = deeper.getBlock();
                }
            }

            variantOpt = registry.selectVariant(biomeId, surfaceBlock, random);
            if (variantOpt.isEmpty()) {
                if (LayerConfig.logNbtTrees()) AronaLayersGen.LOGGER.info("[NbtTrees] SKIP no-variant surface={} biome={} pos={}",
                        Compat.blockId(surfaceBlock), biomeId, surfacePos);
                return false;
            }
        }

        Path nbtPath = variantOpt.get();
        StructureTemplate template = getTemplate(nbtPath, world);
        if (template == null) {
            if (LayerConfig.logNbtTrees()) AronaLayersGen.LOGGER.info("[NbtTrees] SKIP template-null file={} pos={}", nbtPath.getFileName(), surfacePos);
            return false;
        }

        // ArdaTrees-style placement: rotate the trunk anchor around ORIGIN, then subtract
        // from the sapling position so the anchor block lands exactly at surfacePos.
        BlockPos anchor = ANCHOR_CACHE.getOrDefault(nbtPath, BlockPos.ZERO);
        Rotation rotation = ROTATIONS[random.nextInt(ROTATIONS.length)];

        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .setMirror(Mirror.NONE)
            .setIgnoreEntities(true)
            .setKnownShape(false);

        BlockPos rotatedAnchor = StructureTemplate.transform(anchor, Mirror.NONE, rotation, BlockPos.ZERO);
        BlockPos placePos = surfacePos.subtract(rotatedAnchor);

        try {
            template.placeInWorld(world, placePos, placePos, settings, random, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        } catch (RuntimeException e) {
            AronaLayersGen.LOGGER.warn("[NbtTrees] Placement aborted at {} — {}", surfacePos, e.getMessage());
            return false;
        }

        if (LayerConfig.logNbtTrees()) {
            AronaLayersGen.LOGGER.info("[NbtTrees] Placed '{}' rotation={} at {}",
                nbtPath.getFileName(), rotation, surfacePos);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Mode 2: scan-and-replace (RTF-compatible, called from CHUNK_LOAD event)
    // -------------------------------------------------------------------------

    /**
     * Scans a fully-loaded chunk for vanilla log trunk-bases, clears each
     * vanilla tree, and places a CR NBT tree in its place.
     *
     * Vanilla logs are identified by having the AXIS block-state property AND
     * belonging to the "minecraft" namespace — this avoids re-processing CR logs
     * on reload and handles RTF+CR worlds where CR logs lack vanilla block tags.
     */
    public static void scanAndReplace(ServerLevel world, LevelChunk chunk) {
        NbtTreeRegistry registry = NbtTreeRegistry.getInstance();
        var cp = chunk.getPos();
        // Deterministic per-chunk seed, offset from vanilla worldgen seed
        RandomSource rand = RandomSource.create(world.getSeed() ^ Compat.chunkPosToLong(cp) ^ 0x4E4254726565L);

        int startX = cp.getMinBlockX();
        int startZ = cp.getMinBlockZ();
        int bottomY = Compat.minY(world);

        // Track trunk positions already processed to avoid duplicate CR trees
        // (e.g. 2×2 jungle trunks appear in four columns; 2-block radius deduplicates them)
        List<BlockPos> doneTrunks = new ArrayList<>();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                // Start scan just below the motion-blocking heightmap
                int topY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz) - 1;

                BlockPos trunk = null;
                for (int y = topY; y >= bottomY; y--) {
                    BlockPos pos = new BlockPos(wx, y, wz);
                    BlockState st = world.getBlockState(pos);

                    // Only match vanilla logs (minecraft namespace + has AXIS property)
                    if (!st.hasProperty(BlockStateProperties.AXIS)) continue;
                    if (!"minecraft".equals(Ids.namespace(Compat.blockId(st.getBlock())))) continue;

                    BlockState below = world.getBlockState(pos.below());
                    // Trunk base: log with non-log, non-leaf solid block beneath it
                    boolean belowIsLog  = below.hasProperty(BlockStateProperties.AXIS);
                    boolean belowIsLeaf = below.getBlock() instanceof LeavesBlock;
                    if (below.isAir() || belowIsLog || belowIsLeaf) continue;

                    trunk = pos;
                    break;
                }

                if (trunk == null) continue;

                // Skip if too close to an already-processed trunk
                final BlockPos finalTrunk = trunk;
                boolean tooClose = doneTrunks.stream().anyMatch(p ->
                    Math.abs(p.getX() - finalTrunk.getX()) <= 2 &&
                    Math.abs(p.getZ() - finalTrunk.getZ()) <= 2);
                if (tooClose) continue;

                // Biome + registry check
                Optional<ResourceKey<Biome>> biomeOpt = world.getBiome(trunk).unwrapKey();
                if (biomeOpt.isEmpty()) continue;
                String biomeId = Compat.keyId(biomeOpt.get());

                if (!registry.hasBiome(biomeId)) continue;
                if (rand.nextFloat() >= registry.getChance(biomeId)) continue;

                Block surface = world.getBlockState(trunk.below()).getBlock();
                Optional<Path> variantOpt = registry.selectVariant(biomeId, surface, rand);
                if (variantOpt.isEmpty()) continue;

                doneTrunks.add(trunk);

                // Clear vanilla tree, then place CR tree
                clearVanillaTree(world, trunk);
                placeCrTree(world, trunk, variantOpt.get(), rand);
            }
        }
    }

    /**
     * Remove all log and leaf blocks in a generous bounding box around the trunk base.
     * Detects logs via AXIS property and leaves via DISTANCE+PERSISTENT — covers both
     * vanilla and modded variants without relying on block tags.
     */
    private static void clearVanillaTree(ServerLevel world, BlockPos trunk) {
        int radius = 5, height = 22;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= height; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(trunk.getX() + dx, trunk.getY() + dy, trunk.getZ() + dz);
                    BlockState s = world.getBlockState(m);
                    boolean isLog  = s.hasProperty(BlockStateProperties.AXIS);
                    boolean isLeaf = s.getBlock() instanceof LeavesBlock;
                    if (isLog || isLeaf) {
                        world.setBlock(m, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                }
            }
        }
    }

    /** Load CR NBT template and place it at the trunk-base position (Mode 2, currently unused). */
    private static void placeCrTree(ServerLevel world, BlockPos trunk, Path nbtPath, RandomSource rand) {
        StructureTemplate tpl = getTemplate(nbtPath, world);
        if (tpl == null) return;

        BlockPos anchor = ANCHOR_CACHE.getOrDefault(nbtPath, BlockPos.ZERO);
        Rotation rot = ROTATIONS[rand.nextInt(ROTATIONS.length)];

        StructurePlaceSettings sd = new StructurePlaceSettings()
            .setRotation(rot)
            .setMirror(Mirror.NONE)
            .setIgnoreEntities(true)
            .setKnownShape(false);

        BlockPos rotatedAnchor = StructureTemplate.transform(anchor, Mirror.NONE, rot, BlockPos.ZERO);
        BlockPos placePos = trunk.subtract(rotatedAnchor);

        try {
            tpl.placeInWorld(world, placePos, placePos, sd, rand, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        } catch (RuntimeException e) {
            AronaLayersGen.LOGGER.warn("[NbtTrees] CR tree placement error at {}: {}", trunk, e.getMessage());
            return;
        }

        if (LayerConfig.logNbtTrees()) {
            AronaLayersGen.LOGGER.info("[NbtTrees] Replaced vanilla tree with '{}' rotation={} at {}",
                nbtPath.getFileName(), rot, trunk);
        }
    }

    // -------------------------------------------------------------------------
    // Shared utilities
    // -------------------------------------------------------------------------

    private static StructureTemplate getTemplate(Path path, WorldGenLevel world) {
        return TEMPLATE_CACHE.computeIfAbsent(path, p -> loadTemplate(p, world));
    }

    private static StructureTemplate loadTemplate(Path path, WorldGenLevel world) {
        try (InputStream is = Files.newInputStream(path)) {
            CompoundTag nbt = Compat.readCompressedNbt(is);
            ANCHOR_CACHE.put(path, computeAnchor(nbt));
            StructureTemplate template = new StructureTemplate();
            template.load(world.registryAccess().lookupOrThrow(Registries.BLOCK), nbt);
            return template;
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("[NbtTrees] Failed to load '{}': {}", path.getFileName(), e.getMessage());
            return null;
        }
    }

    // Finds the lowest log/stem/trunk/hyphae block in the NBT palette — used as the
    // anchor so the trunk base aligns exactly with the sapling position (ArdaTrees approach).
    private static BlockPos computeAnchor(CompoundTag nbt) {
        if (!nbt.contains("blocks") || !nbt.contains("palette")) return BlockPos.ZERO;

        ListTag palette = Compat.nbtList(nbt, "palette");
        ListTag blocks  = Compat.nbtList(nbt, "blocks");

        boolean[] isLog = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            String name = Compat.nbtString(Compat.nbtCompound(palette, i), "Name");
            isLog[i] = name.contains("log") || name.contains("stem")
                    || name.contains("trunk") || name.contains("hyphae");
        }

        BlockPos best = null;
        int bestY = Integer.MAX_VALUE;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = Compat.nbtCompound(blocks, i);
            if (!isLog[Compat.nbtInt(block, "state")]) continue;
            ListTag pos = Compat.nbtIntList(block, "pos");
            int y = Compat.nbtInt(pos, 1);
            if (y < bestY) {
                bestY = y;
                best = new BlockPos(Compat.nbtInt(pos, 0), y, Compat.nbtInt(pos, 2));
            }
        }
        return best != null ? best : BlockPos.ZERO;
    }
}
