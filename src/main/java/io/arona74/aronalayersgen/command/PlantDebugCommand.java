package io.arona74.aronalayersgen.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /algdebug plant}: plant grounding as the server sees it, for the column you look at.
 *
 * <p>Lists every block from two below the plant's base to two above its top with its id, whether
 * its block type is a grounded plant, whether this exact state is, and the offset it gets. A tall
 * plant whose parts disagree shows here which part stops following the others.
 */
public final class PlantDebugCommand {
    private PlantDebugCommand() {}

    static int lookedAt(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, "[ALG plant] Must be run by a player.");
            return 0;
        }
        HitResult hit = player.pick(20.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            send(source, "[ALG plant] Look at a plant (within 20 blocks).");
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos looked = blockHit.getBlockPos();
        BlockPos base = looked;
        while (PlantGrounding.isGroundedBlock(level.getBlockState(base.below()).getBlock())) base = base.below();
        BlockPos top = looked;
        while (PlantGrounding.isGroundedBlock(level.getBlockState(top.above()).getBlock())) top = top.above();

        send(source, "[ALG plant] column at " + looked.getX() + "," + looked.getZ()
            + " (base Y=" + base.getY() + ", top Y=" + top.getY() + ")");
        for (int y = top.getY() + 2; y >= base.getY() - 2; y--) {
            BlockPos pos = new BlockPos(looked.getX(), y, looked.getZ());
            BlockState state = level.getBlockState(pos);
            boolean blockGrounded = PlantGrounding.isGroundedBlock(state.getBlock());
            boolean stateGrounded = PlantGrounding.isGrounded(state);
            String offset = stateGrounded ? String.format("%.3f", PlantGrounding.offset(state, level, pos)) : "-";
            StringBuilder props = new StringBuilder();
            for (var property : state.getProperties()) {
                if (property.getName().equals("facing")) continue;
                props.append(props.length() == 0 ? " [" : ",").append(property.getName()).append('=')
                    .append(state.getValue(property));
            }
            if (props.length() > 0) props.append(']');
            send(source, String.format("  Y=%d %s%s  type=%s state=%s offset=%s%s",
                y, Compat.blockId(state.getBlock()), props,
                blockGrounded ? "yes" : "no", stateGrounded ? "yes" : "no", offset,
                PlantGrounding.isLayerSoil(state) ? "  [layer soil]" : ""));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}
