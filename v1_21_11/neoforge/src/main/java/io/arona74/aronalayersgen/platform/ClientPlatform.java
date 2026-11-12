package io.arona74.aronalayersgen.platform;

import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge, Minecraft 1.21.11: client-only loader services used by the shared code. */
public final class ClientPlatform {
    private ClientPlatform() {}

    /** A client-side command with no arguments, e.g. /algconfig. */
    public static void registerClientCommand(String name, Runnable action) {
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event ->
                event.getDispatcher().register(Commands.literal(name).executes(ctx -> {
                    action.run();
                    return 1;
                })));
    }
}
