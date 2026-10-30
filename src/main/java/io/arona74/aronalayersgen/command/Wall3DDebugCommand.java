package io.arona74.aronalayersgen.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.injection.Layer3D;
import io.arona74.aronalayersgen.injection.Layer3DWalls;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.RTFCompat;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.EnumMap;
import java.util.Map;

/**
 * Diagnostics for the TerraForged wall pass (see Layer3DWalls). Places nothing: runs the pass's own
 * analysis on the generated world, reading the pieces it placed as what they replaced, so it reports
 * the decision that made each cell.
 *
 * <ul>
 *   <li>{@code /algdebug wall3d}: the cell you are looking at, with each of its four side neighbours.</li>
 *   <li>{@code /algdebug walls3d}: every cell of the current chunk the pass considers, tallied by
 *       verdict, with the side verdicts and the thickness distribution.</li>
 * </ul>
 */
public final class Wall3DDebugCommand {
    private Wall3DDebugCommand() {}

    static int lookedAt(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, "[ALG wall3d] Must be run by a player.");
            return 0;
        }
        Object randomState = terraForgedState(source, "[ALG wall3d]");
        if (randomState == null) return 0;

        HitResult hit = player.pick(20.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            send(source, "[ALG wall3d] Look at a block (within 20 blocks).");
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos cell = targetCell(level, blockHit);
        ChunkAccess chunk = level.getChunk(cell.getX() >> 4, cell.getZ() >> 4);
        int lx = cell.getX() & 15, lz = cell.getZ() & 15;

        boolean ok = RTFCompat.withCells(chunk, randomState, (scale, cells) -> {
            Layer3DWalls.Terrain terrain = Layer3DWalls.Terrain.read(chunk, scale, cells,
                (x, z) -> level.getChunkSource().getChunkNow(x, z));
            Layer3DWalls.Analysis a = Layer3DWalls.analyse(terrain, lx, lz, cell.getY(), true);

            send(source, "[ALG wall3d] cell " + cell.getX() + "," + cell.getY() + "," + cell.getZ()
                + " (local " + lx + "," + lz + ") holds " + level.getBlockState(cell));
            send(source, String.format("  column: surface=%.3f (ground=%d + floor layer)  cell height=%.3f%s",
                a.sB(), a.gB(), a.hB(), a.floorLayers() > 0 ? "  floor layers=" + a.floorLayers() : ""));
            float[] up = Layer3DWalls.uphill(terrain, lx, lz);
            send(source, String.format("  uphill: east %+.3f  south %+.3f  (the wall faces the qualifying side most uphill)",
                up[0], up[1]));
            StringBuilder open = new StringBuilder();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (Layer3DWalls.opensOnto(terrain, lx, lz, cell.getY(), d)) open.append(' ').append(d.getName());
            }
            send(source, "  wall sides facing open ground:" + (open.length() > 0 ? open : " none")
                + "  (preferred before uphill)");
            send(source, "  verdict: " + a.verdict() + " - " + a.verdict().description);
            for (Layer3DWalls.Side s : a.sides()) send(source, "  " + describe(s, a.gB()));
            if (a.piece() != null) {
                BlockState now = level.getBlockState(cell);
                send(source, "  piece: " + a.piece() + (now.equals(a.piece()) ? "  (in the world)" : "  (world differs)"));
            }
        });
        if (!ok) cellsUnavailable(source, "[ALG wall3d]");
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    static int chunkScan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, "[ALG walls3d] Must be run by a player.");
            return 0;
        }
        Object randomState = terraForgedState(source, "[ALG walls3d]");
        if (randomState == null) return 0;

        ServerLevel level = source.getLevel();
        ChunkPos cp = Compat.chunkPosOf(player.blockPosition());
        ChunkAccess chunk = level.getChunk(Compat.chunkX(cp), Compat.chunkZ(cp));

        boolean ok = RTFCompat.withCells(chunk, randomState, (scale, cells) -> {
            Layer3DWalls.Terrain terrain = Layer3DWalls.Terrain.read(chunk, scale, cells,
                (x, z) -> level.getChunkSource().getChunkNow(x, z));
            Map<Layer3DWalls.CellVerdict, Integer> verdicts = new EnumMap<>(Layer3DWalls.CellVerdict.class);
            Map<Layer3DWalls.SideVerdict, Integer> sideVerdicts = new EnumMap<>(Layer3DWalls.SideVerdict.class);
            int[] thickness = new int[8];
            int floors3D = 0, walls = 0, differ = 0, flatFloors = 0, borderOnly = 0;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (Float.isNaN(terrain.height()[lx][lz])) {
                        verdicts.merge(Layer3DWalls.CellVerdict.NO_CELL, 1, Integer::sum);
                        continue;
                    }
                    int gB = terrain.ground()[lx][lz];
                    int top = terrain.topBeside(lx, lz);
                    if (top <= gB) {
                        // Never visited by the pass: a floor layer here stays a normal layer.
                        BlockState floor = chunk.getBlockState(new BlockPos(terrain.startX() + lx, gB, terrain.startZ() + lz));
                        if (LayerPlacementHelper.isFloorLayer(floor)) flatFloors++;
                        continue;
                    }
                    for (int y = gB; y < top; y++) {
                        Layer3DWalls.Analysis a = Layer3DWalls.analyse(terrain, lx, lz, y, true);
                        verdicts.merge(a.verdict(), 1, Integer::sum);
                        if (a.verdict() == Layer3DWalls.CellVerdict.OCCUPIED || a.verdict() == Layer3DWalls.CellVerdict.BELOW_GROUND) continue;
                        boolean anyBorder = false;
                        for (Layer3DWalls.Side s : a.sides()) {
                            sideVerdicts.merge(s.verdict(), 1, Integer::sum);
                            if (s.verdict() == Layer3DWalls.SideVerdict.BORDER) anyBorder = true;
                        }
                        if (a.verdict() == Layer3DWalls.CellVerdict.PIECE) {
                            for (Layer3DWalls.Side s : a.sides()) {
                                if (s.verdict() == Layer3DWalls.SideVerdict.CHOSEN) thickness[s.thickness()]++;
                            }
                            if (a.floorLayers() > 0) floors3D++; else walls++;
                            if (!chunk.getBlockState(a.pos()).equals(a.piece())) differ++;
                        } else if (a.verdict() == Layer3DWalls.CellVerdict.TROUGH) {
                            if (!chunk.getBlockState(a.pos()).equals(a.piece())) differ++;
                        } else if (anyBorder) {
                            borderOnly++;
                        }
                    }
                }
            }

            send(source, "[ALG walls3d] chunk " + Compat.chunkX(cp) + "," + Compat.chunkZ(cp)
                + ": " + floors3D + " floor layers -> 3D, " + walls + " wall pieces, "
                + flatFloors + " floor layers on flat ground (stay normal)");
            for (Map.Entry<Layer3DWalls.CellVerdict, Integer> e : verdicts.entrySet()) {
                send(source, String.format("  %-13s %5d  %s", e.getKey(), e.getValue(), e.getKey().description));
            }
            StringBuilder sb = new StringBuilder("  sides:");
            sideVerdicts.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            send(source, sb.toString());
            StringBuilder th = new StringBuilder("  thickness (layers):");
            for (int i = 1; i <= 7; i++) th.append(' ').append(i).append('=').append(thickness[i]);
            send(source, th.toString());
            if (borderOnly > 0) {
                send(source, "  " + borderOnly + " cells without a piece touch the chunk border, where a step may be missed");
            }
            if (differ > 0) {
                send(source, "  " + differ + " pieces differ from the world: generated before this rule, with the option off,"
                    + " or the ground changed since");
            }
        });
        if (!ok) cellsUnavailable(source, "[ALG walls3d]");
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    /**
     * The cell a piece would occupy: a layer block you look at is the cell itself; otherwise the
     * cell in front of the face you look at.
     */
    private static BlockPos targetCell(ServerLevel level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (LayerPlacementHelper.hasLayerProperty(level.getBlockState(pos))) return pos;
        return pos.relative(hit.getDirection());
    }

    private static String describe(Layer3DWalls.Side s, int gB) {
        String head = String.format("%-5s %-14s", s.dir().getName(), s.verdict());
        return switch (s.verdict()) {
            case BORDER -> head + " " + s.verdict().description;
            case NO_CELL -> head + " " + s.verdict().description;
            case NOT_HIGHER -> String.format("%s surface=%.3f", head, s.sA());
            case STEP_TOO_SMALL -> String.format("%s surface=%.3f ground=%d: step %d, needs %d",
                head, s.sA(), s.gA(), s.gA() - gB, s.minStep());
            case CHOSEN, BEATEN, TOO_THIN, NO_PIECE, SOFT_MATERIAL -> String.format("%s surface=%.3f ground=%d step=%d  t=%.3f thickness=%d%s",
                head, s.sA(), s.gA(), s.gA() - gB, s.t(), s.thickness(),
                s.verdict() == Layer3DWalls.SideVerdict.CHOSEN ? "" : "  (" + s.verdict().description + ")");
            default -> String.format("%s surface=%.3f ground=%d  %s", head, s.sA(), s.gA(), s.verdict().description);
        };
    }

    /** The TerraForged random state, or null (reported) when this world is not TerraForged. */
    private static Object terraForgedState(CommandSourceStack source, String tag) {
        if (!RTFCompat.isRTFAvailable()) {
            send(source, tag + " Needs FreeTerraForged or ReTerraForged (RTF support not bound, see the log).");
            return null;
        }
        Object randomState = RandomStateHolder.noiseConfigFor(source.getLevel());
        if (randomState == null) randomState = RandomStateHolder.getRandomState();
        if (randomState == null) {
            send(source, tag + " No TerraForged state for this world.");
            return null;
        }
        if (!Layer3D.isActive()) {
            send(source, tag + " Note: 3D_layer_injection is off or VanillaLayer+ has no 3D layers; showing what it would do.");
        }
        return randomState;
    }

    private static void cellsUnavailable(CommandSourceStack source, String tag) {
        send(source, tag + " This chunk's TerraForged cells are not available (not a TerraForged world, or the"
            + " server no longer holds them; generation reads them while the chunk is built).");
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}
