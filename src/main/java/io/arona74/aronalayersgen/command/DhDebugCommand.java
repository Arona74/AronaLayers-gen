package io.arona74.aronalayersgen.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.compat.dh.DhStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Distant Horizons exact-height diagnostics (DhStats): how long each hook takes next to the DH
 * work it sits in, and switches to build LODs without the refine pass or the column view trim.
 *
 * <ul>
 *   <li>{@code /algdebug dh}: timings since the last reset (also written to the log).</li>
 *   <li>{@code /algdebug dh reset}: start a new measurement.</li>
 *   <li>{@code /algdebug dh skip <none|refine>}: turn the refine pass off for new LODs.</li>
 *   <li>{@code /algdebug dh skip trim}: toggle the column view trim in mesh builds.</li>
 * </ul>
 */
public final class DhDebugCommand {
    private DhDebugCommand() {}

    static int report(CommandContext<CommandSourceStack> ctx) {
        for (String line : DhStats.report()) {
            send(ctx.getSource(), line);
            AronaLayersGen.LOGGER.info(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    static int reset(CommandContext<CommandSourceStack> ctx) {
        DhStats.reset();
        send(ctx.getSource(), "[ALG dh] timings reset.");
        return Command.SINGLE_SUCCESS;
    }

    static int skipTrim(CommandContext<CommandSourceStack> ctx) {
        DhStats.skipTrim = !DhStats.skipTrim;
        DhStats.reset();
        send(ctx.getSource(), "[ALG dh] column view trim " + (DhStats.skipTrim ? "off" : "on") + " from now on; timings reset.");
        return Command.SINGLE_SUCCESS;
    }

    static int skip(CommandContext<CommandSourceStack> ctx, boolean refine) {
        DhStats.skipRefine = refine;
        DhStats.skipTrim = false;
        DhStats.reset();
        send(ctx.getSource(), "[ALG dh] skipping " + (refine ? "refine" : "nothing") + " from now on; timings reset.");
        return Command.SINGLE_SUCCESS;
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}
