package io.arona74.aronalayersgen.platform;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fabric, Minecraft 26.1.2: loader services used by the shared code. Every loader subproject has a
 * class with this name and these methods; vanilla differences between Minecraft versions are in
 * Compat instead. Client-only services are in ClientPlatform.
 */
public final class Platform {
    private Platform() {}

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** Server commands, added to every dispatcher the server builds. */
    public static void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> registrar) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registrar.accept(dispatcher));
    }

    public static void onChunkLoad(BiConsumer<ServerLevel, LevelChunk> handler) {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> handler.accept(level, chunk));
    }

    public static void onServerTickEnd(Runnable task) {
        ServerTickEvents.END_SERVER_TICK.register(server -> task.run());
    }
}
