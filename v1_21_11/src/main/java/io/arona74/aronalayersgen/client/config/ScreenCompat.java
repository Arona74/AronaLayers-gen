package io.arona74.aronalayersgen.client.config;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Version seam for the config screen (Minecraft 1.21.11). The shared screen code calls only
 * these; everything else it uses is identical across the supported versions.
 */
public final class ScreenCompat {
    private ScreenCompat() {}

    public static <T> CycleButton.Builder<T> cycle(Function<T, Component> format, List<T> values, T initial) {
        return CycleButton.builder(format, initial).withValues(values);
    }

    public static CycleButton.Builder<Boolean> onOff(boolean initial) {
        return CycleButton.onOffBuilder(initial);
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    public static void openPath(Path path) {
        net.minecraft.util.Util.getPlatform().openPath(path);
    }

    /** A client command literal (Fabric renamed ClientCommandManager to ClientCommands for 26.x). */
    public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return ClientCommandManager.literal(name);
    }

    /** Runs on the client thread after the current tick, e.g. once the chat screen has closed. */
    public static void schedule(Runnable task) {
        Minecraft.getInstance().schedule(task);
    }
}
