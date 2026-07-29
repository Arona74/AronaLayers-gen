package io.arona74.aronalayersgen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
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
 * <p>1.21.11 renames this version's {@code ResourceLocation} to {@code Identifier}.
 * Java has no type aliases, so shared code cannot name either type: it passes ids
 * around as canonical {@code "namespace:path"} strings and calls the helpers below
 * at the Minecraft boundary.
 */
public final class Compat {
    private Compat() {}

    public static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static CompoundTag readCompressedNbt(InputStream is) throws IOException {
        return NbtIo.readCompressed(is);
    }

    /** Canonical {@code "namespace:path"} form, or null if the id is malformed. */
    public static String normalizeId(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
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
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) return Blocks.AIR;
        return BuiltInRegistries.BLOCK.get(parsed);
    }

    /** Registry id of a block, in canonical string form. */
    public static String blockId(Block block) {
        var key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? null : key.toString();
    }

    /** Canonical string form of a registry key (biome keys and the like). */
    public static String keyId(ResourceKey<?> key) {
        return key.location().toString();
    }

    /** 1.21.11 renamed LevelHeightAccessor.getMinBuildHeight/getMaxBuildHeight to getMinY/getMaxY. */
    public static int minY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMinBuildHeight();
    }

    public static int maxY(net.minecraft.world.level.LevelHeightAccessor level) {
        return level.getMaxBuildHeight();
    }

    // ---- NBT ------------------------------------------------------------
    // 1.21.11 made these accessors return Optional; the *Or/OrEmpty variants
    // there reproduce the behaviour this version has by default.

    public static ListTag nbtList(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_COMPOUND);
    }

    public static ListTag nbtIntList(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_INT);
    }

    public static net.minecraft.nbt.CompoundTag nbtCompound(ListTag list, int index) {
        return list.getCompound(index);
    }

    public static String nbtString(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getString(key);
    }

    public static int nbtInt(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getInt(key);
    }

    public static int nbtInt(ListTag list, int index) {
        return list.getInt(index);
    }

    // ---- world / blocks -------------------------------------------------

    /** 1.21.11 changed the third argument from a boolean 'moved' flag to int flags. */
    public static BlockState chunkSetBlockState(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return chunk.setBlockState(pos, state, false);
    }

    /** 1.21.11 added a sea-level parameter to this. */
    public static boolean coldEnoughToSnow(Biome biome, BlockPos pos) {
        return biome.coldEnoughToSnow(pos);
    }

    /**
     * Block settings copied from another block.
     *
     * <p>Kept on FabricBlockSettings here on purpose: its copyOf() takes the source
     * block's own Properties instance via an accessor mixin, which is not equivalent
     * to either vanilla ofFullCopy or ofLegacyCopy. Preserving it avoids changing the
     * behaviour of a shipped version. {@code path} is unused until 1.21.2+, where
     * settings must carry their registry key.
     */
    public static BlockBehaviour.Properties blockSettings(net.minecraft.world.level.block.Block copyFrom, String path) {
        return net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings.copyOf(copyFrom);
    }

    public static Item.Properties itemSettings(String path) {
        return new Item.Properties();
    }

    /** 1.21.11 replaced integer permission levels with PermissionSet. */
    public static boolean hasPermission(CommandSourceStack source, int level) {
        return source.hasPermission(level);
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
