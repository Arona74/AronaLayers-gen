package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Ids;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.NbtTreeRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
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
 * Handles CR NBT tree placement by direct interception at the source:
 *
 * <ul>
 *   <li>Vanilla worldgen / sapling growth — tryPlaceTree() is called from NbtTreeFeatureMixin when
 *       TreeFeature.generate() runs.</li>
 *   <li>Tellus custom-tree worldgen — TellusProceduralTreeMixin cancels Tellus's procedural
 *       generator and defers a CR tree via queueWorldgenTree(), placed on the next server tick.</li>
 * </ul>
 *
 * <p>Both routes converge on tryPlaceTree(), which places the tree only where the trunk base sits in
 * open, at-surface space. There is deliberately no post-hoc "scan finished chunks for stray logs"
 * fallback: it could not distinguish a surface tree from a buried log and stamped CR trees inside
 * terrain (notably deepslate crevices on stony_peaks). Trees the intercept does not handle are left
 * as the generator placed them.
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
        boolean configured = NbtTreeRegistry.getInstance().hasBiome(Compat.keyId(biomeKey.get()));
        if (!configured && LayerConfig.logNbtTrees())
            AronaLayersGen.LOGGER.info("[NbtTrees] fallback (unconfigured biome) biome={} pos={} — Tellus tree kept",
                Compat.keyId(biomeKey.get()), pos);
        return configured;
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
        if (!READY_CHUNKS.isEmpty()) {
            List<Map.Entry<Long, ServerLevel>> batch = new ArrayList<>(READY_CHUNKS.entrySet());
            for (Map.Entry<Long, ServerLevel> e : batch) {
                READY_CHUNKS.remove(e.getKey());
                placeDeferredTrees(e.getValue(), e.getKey());
            }
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

        // Clearance guard — the trunk base (surfacePos) must sit in open, replaceable space AND at
        // (not below) the live surface. Tellus's own place() aborts via hasTrunkClearance() when a
        // column has no room for the trunk (buried spots, overhangs, or a stale/low ground Y at
        // extreme world scale), which is why disabling this feature leaves those columns bare. Our
        // mixin cancels at HEAD, before that guard, so without this check we stamp the tree inside
        // solid rock — its sparse conquest foliage replaces deepslate and reads as an "underground
        // crater". A growing sapling's own position is already air, so this never blocks saplings.
        // Logged at WARN so it is visible without debug flags while we confirm the cause.
        BlockState atOrigin = world.getBlockState(surfacePos);
        int liveSurfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, surfacePos.getX(), surfacePos.getZ());
        boolean solid  = !atOrigin.isAir() && !atOrigin.canBeReplaced();
        boolean buried = surfacePos.getY() < liveSurfaceY - 2;
        if (solid || buried) {
            AronaLayersGen.LOGGER.warn("[NbtTrees] SKIP buried tryPlaceTree block={} origin={} originY={} liveSurfaceY={} (solid={} buried={})",
                Compat.blockId(atOrigin.getBlock()), surfacePos, surfacePos.getY(), liveSurfaceY, solid, buried);
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
            .setKnownShape(false)
            // Non-destructive: skip the template's air (and structure) cells so the tree only adds
            // logs/leaves and never carves terrain. On steep biomes (stony_peaks) the template's
            // bounding box sinks into the rising slope; without this its air blocks would overwrite
            // the mountain into craters.
            .addProcessor(BlockIgnoreProcessor.STRUCTURE_AND_AIR);

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
