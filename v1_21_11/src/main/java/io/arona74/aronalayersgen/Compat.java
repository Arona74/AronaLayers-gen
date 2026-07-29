package io.arona74.aronalayersgen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.io.IOException;
import java.io.InputStream;

/**
 * Version seam. Everything whose Minecraft API differs across the supported
 * versions goes through here so the shared sources stay identical.
 *
 * <p>1.21.11 renamed {@code ResourceLocation} to {@code Identifier}. Java has no
 * type aliases, so shared code cannot name either type: it passes ids around as
 * canonical {@code "namespace:path"} strings and calls the helpers below at the
 * Minecraft boundary.
 *
 * <p>Note also that {@code Registry.get(Identifier)} still exists here but now
 * returns {@code Optional<Holder.Reference<T>>} — the value lookup is
 * {@code getValue}. Routing through this class is what keeps that from silently
 * changing meaning between versions.
 */
public final class Compat {
    private Compat() {}

    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static CompoundTag readCompressedNbt(InputStream is) throws IOException {
        return NbtIo.readCompressed(is, NbtAccounter.create(Long.MAX_VALUE));
    }

    /** Canonical {@code "namespace:path"} form, or null if the id is malformed. */
    public static String normalizeId(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? null : parsed.toString();
    }

    /**
     * Block for an id string.
     *
     * <p>Returns {@link Blocks#AIR} when the id is malformed or not registered,
     * matching what the registry lookup itself returns. Callers rely on that
     * sentinel — several cache a miss as AIR, so returning null instead would
     * defeat those caches and re-query the registry on every call.
     */
    public static Block blockFromId(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) return Blocks.AIR;
        return BuiltInRegistries.BLOCK.getValue(parsed);
    }

    /** Registry id of a block, in canonical string form. */
    public static String blockId(Block block) {
        var key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? null : key.toString();
    }

    /** Canonical string form of a registry key (biome keys and the like). */
    public static String keyId(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    /** 1.21.11 renamed LevelHeightAccessor.getMinBuildHeight/getMaxBuildHeight to getMinY/getMaxY. */
    public static int minY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMinY();
    }

    public static int maxY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMaxY();
    }

    // ---- NBT ------------------------------------------------------------
    // These accessors return Optional in this version; the *Or/OrEmpty variants
    // reproduce the defaults the older versions had.

    public static ListTag nbtList(CompoundTag tag, String key) {
        return tag.getListOrEmpty(key);
    }

    public static ListTag nbtIntList(CompoundTag tag, String key) {
        return tag.getListOrEmpty(key);
    }

    public static CompoundTag nbtCompound(ListTag list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    public static String nbtString(CompoundTag tag, String key) {
        return tag.getStringOr(key, "");
    }

    public static int nbtInt(CompoundTag tag, String key) {
        return tag.getIntOr(key, 0);
    }

    public static int nbtInt(ListTag list, int index) {
        return list.getIntOr(index, 0);
    }

    // ---- world / blocks -------------------------------------------------

    /** The third argument became int flags here; the two-arg overload is the plain write. */
    public static BlockState chunkSetBlockState(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return chunk.setBlockState(pos, state);
    }

    /**
     * This version takes the sea level explicitly. 63 is vanilla's default and matches
     * what the older versions used internally, so default worlds behave as before.
     */
    public static boolean coldEnoughToSnow(Biome biome, BlockPos pos) {
        return biome.coldEnoughToSnow(pos, 63);
    }

    /**
     * FabricBlockSettings is gone in this version, so this uses vanilla. ofLegacyCopy
     * is the closer match to the old copy semantics (ofFullCopy would also carry over
     * the source block's loot table, overriding our own). Settings must also carry a
     * registry key from 1.21.2 onward.
     */
    public static BlockBehaviour.Properties blockSettings(net.minecraft.world.level.block.Block copyFrom, String path) {
        return BlockBehaviour.Properties.ofLegacyCopy(copyFrom)
                .setId(ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,
                        Identifier.fromNamespaceAndPath(AronaLayersGen.MOD_ID, path)));
    }

    public static Item.Properties itemSettings(String path) {
        return new Item.Properties()
                .setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,
                        Identifier.fromNamespaceAndPath(AronaLayersGen.MOD_ID, path)));
    }

    /** Integer permission levels became PermissionSet here; byId maps the old levels over. */
    public static boolean hasPermission(CommandSourceStack source, int level) {
        return source.permissions().hasPermission(
                new net.minecraft.server.permissions.Permission.HasCommandLevel(
                        net.minecraft.server.permissions.PermissionLevel.byId(level)));
    }

    // ---- ChunkPos -------------------------------------------------------
    // 26.2 turned ChunkPos into a record: the x/z fields became accessors, and
    // the BlockPos/long constructors and toLong/asLong were renamed.

    public static int chunkX(net.minecraft.world.level.ChunkPos pos) {
        return pos.x;
    }

    public static int chunkZ(net.minecraft.world.level.ChunkPos pos) {
        return pos.z;
    }

    public static net.minecraft.world.level.ChunkPos chunkPosOf(net.minecraft.core.BlockPos pos) {
        return new net.minecraft.world.level.ChunkPos(pos);
    }

    public static long chunkPosToLong(net.minecraft.world.level.ChunkPos pos) {
        return pos.toLong();
    }

    public static long chunkPosAsLong(int x, int z) {
        return net.minecraft.world.level.ChunkPos.asLong(x, z);
    }

    /**
     * 26.2 added a third (boolean) parameter to the chunk-load callback, so the
     * lambda arity differs per version and the registration cannot be shared.
     */
    public static void registerChunkLoad(java.util.function.BiConsumer<
            net.minecraft.server.level.ServerLevel,
            net.minecraft.world.level.chunk.LevelChunk> handler) {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(
                (world, chunk) -> handler.accept(world, chunk));
    }
}
