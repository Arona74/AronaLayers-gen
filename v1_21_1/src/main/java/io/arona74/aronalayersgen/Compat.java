package io.arona74.aronalayersgen;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;

public final class Compat {
    private Compat() {}

    public static Identifier id(String namespace, String path) {
        return Identifier.of(namespace, path);
    }

    public static NbtCompound readCompressedNbt(InputStream is) throws IOException {
        return NbtIo.readCompressed(is, NbtSizeTracker.of(Long.MAX_VALUE));
    }
}
