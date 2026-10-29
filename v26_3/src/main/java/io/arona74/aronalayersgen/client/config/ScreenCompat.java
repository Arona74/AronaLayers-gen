package io.arona74.aronalayersgen.client.config;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Version seam for the config screen (Minecraft 26.3: screens are set on Gui, folders opened by Blaze3D). The shared screen code calls only
 * these; everything else it uses is identical across the supported versions.
 */
public final class ScreenCompat {
    private ScreenCompat() {}

    public static <T> CycleButton.Builder<T> cycle(Function<T, Component> format, T initial) {
        return CycleButton.builder(format, initial);
    }

    public static CycleButton.Builder<Boolean> onOff(boolean initial) {
        return CycleButton.onOffBuilder(initial);
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    public static void openPath(Path path) {
        com.mojang.blaze3d.Blaze3D.openPath(path);
    }

    /** A client command literal (Fabric renamed ClientCommandManager to ClientCommands for 26.x). */
    public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return ClientCommands.literal(name);
    }

    /** Runs on the client thread after the current tick, e.g. once the chat screen has closed. */
    public static void schedule(Runnable task) {
        Minecraft.getInstance().schedule(task);
    }

    /** A section heading in the option list. */
    public static void addHeader(OptionsList list, Component text, Font font) {
        list.addHeader(text);
    }
}
