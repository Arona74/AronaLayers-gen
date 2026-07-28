package io.arona74.aronalayersgen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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

    /** Block for an id string, or null if the id is malformed or not registered. */
    public static Block blockFromId(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) return null;
        Block block = BuiltInRegistries.BLOCK.get(parsed);
        return block == Blocks.AIR ? null : block;
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
}
