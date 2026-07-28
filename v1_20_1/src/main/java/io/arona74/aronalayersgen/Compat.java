package io.arona74.aronalayersgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;

public final class Compat {
    private Compat() {}

    public static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static CompoundTag readCompressedNbt(InputStream is) throws IOException {
        return NbtIo.readCompressed(is);
    }
}
