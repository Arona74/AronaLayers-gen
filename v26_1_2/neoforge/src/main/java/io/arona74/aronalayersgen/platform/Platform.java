package io.arona74.aronalayersgen.platform;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * NeoForge, Minecraft 26.1.2: loader services used by the shared code. Every loader subproject
 * has a class with this name and these methods; vanilla differences between Minecraft versions
 * are in Compat instead. Client-only services are in ClientPlatform.
 */
public final class Platform {
    private Platform() {}

    /**
     * ModList exists once mods start loading; the mixin plugin asks earlier, while only the list
     * of mod files found is known.
     */
    public static boolean isModLoaded(String modId) {
        ModList mods = ModList.get();
        if (mods != null) return mods.isLoaded(modId);
        return LoadingModList.get().getModFileById(modId) != null;
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Server commands, added to every dispatcher the server builds. */
    public static void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> registrar) {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> registrar.accept(event.getDispatcher()));
    }

    /** A chunk loaded into a server level, as Fabric's ServerChunkEvents.CHUNK_LOAD. */
    public static void onChunkLoad(BiConsumer<ServerLevel, LevelChunk> handler) {
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
                handler.accept(level, chunk);
            }
        });
    }

    public static void onServerTickEnd(Runnable task) {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> task.run());
    }
}
