package io.arona74.aronalayersgen.platform;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/** Fabric, Minecraft 1.21.11: client-only loader services used by the shared code. */
public final class ClientPlatform {
    private ClientPlatform() {}

    /** A client-side command with no arguments, e.g. /algconfig. */
    public static void registerClientCommand(String name, Runnable action) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(ClientCommandManager.literal(name).executes(ctx -> {
                    action.run();
                    return 1;
                })));
    }
}
