package io.arona74.aronalayersgen.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.injection.FractionalSurfaceSampler;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import io.arona74.aronalayersgen.injection.WallLayers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.EnumMap;
import java.util.Map;

/**
 * Wall layer diagnostics (see WallLayers). Places nothing: reports what wall layer placement
 * would do, so the rules can be checked against real cliffs before anything is written.
 *
 * <ul>
 *   <li>{@code /algdebug wall}: the side face you are looking at, in detail.</li>
 *   <li>{@code /algdebug walls}: every open-air side face of the current chunk, tallied by verdict,
 *       with the thickness and normal distribution of the faces that would get a piece.</li>
 * </ul>
 */
public final class WallDebugCommand {
    private WallDebugCommand() {}

    static int lookedAt(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, "[ALG wall] Must be run by a player.");
            return 0;
        }
        FractionalSurfaceSampler sampler = sampler(source);
        if (sampler == null) return 0;

        HitResult hit = player.pick(20.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            send(source, "[ALG wall] Look at the side of a block (within 20 blocks).");
            return 0;
        }
        Direction out = blockHit.getDirection();
        if (out.getAxis() == Direction.Axis.Y) {
            send(source, "[ALG wall] That is a " + out.getName() + " face. Look at the side of a block.");
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos solid = blockHit.getBlockPos();
        WallLayers.Face f = WallLayers.analyse(level, firstFree(level), sampler, solid, out);

        send(source, "[ALG wall] " + Compat.blockId(level.getBlockState(solid).getBlock())
            + " at " + solid.getX() + "," + solid.getY() + "," + solid.getZ() + ", face " + out.getName());
        send(source, String.format("  field: solid=%.4f air=%.4f  crossing=%s  layers raw=%d placed=%d",
            f.dSolid(), f.dAir(), f.crossing() < 0 ? "none" : String.format("%.3f", f.crossing()),
            f.rawLayers(), f.layers()));
        send(source, String.format("  normal: x=%.2f y=%.2f z=%.2f  sideways=%.2f (wall from %.2f)",
            f.nx(), f.ny(), f.nz(), f.horizontal(), WallLayers.WALL_MIN_HORIZONTAL));
        send(source, "  verdict: " + f.verdict() + " - " + f.verdict().description);
        if (f.piece() != null) {
            send(source, "  would place: " + f.piece() + " at "
                + solid.relative(out).getX() + "," + solid.relative(out).getY() + "," + solid.relative(out).getZ());
        }
        return Command.SINGLE_SUCCESS;
    }

    static int chunkScan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, "[ALG walls] Must be run by a player.");
            return 0;
        }
        FractionalSurfaceSampler sampler = sampler(source);
        if (sampler == null) return 0;

        ServerLevel level = source.getLevel();
        ChunkPos cp = Compat.chunkPosOf(player.blockPosition());
        int startX = cp.getMinBlockX(), startZ = cp.getMinBlockZ();
        int bottomY = Compat.minY(level);

        java.util.function.IntBinaryOperator firstFree = firstFree(level);
        Map<WallLayers.Verdict, Integer> verdicts = new EnumMap<>(WallLayers.Verdict.class);
        Map<String, Integer> fieldNotSolidBlocks = new java.util.HashMap<>();
        int borderWalls = 0;
        int[] layerHist = new int[9];
        int[] sidewaysHist = new int[6]; // 0.70-0.75 ... 0.90-0.95, 0.95+
        int faces = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = startX + lx, z = startZ + lz;
                int top = firstFree.applyAsInt(x, z) - 1;
                for (int y = top; y > Math.max(bottomY, top - WallLayers.SCAN_DEPTH); y--) {
                    BlockPos solid = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(solid);
                    if (!WallLayers.isTerrainCandidate(state)) continue;
                    for (Direction out : Direction.Plane.HORIZONTAL) {
                        if (!level.getBlockState(solid.relative(out)).isAir()) continue;
                        WallLayers.Face f = WallLayers.analyse(level, firstFree, sampler, solid, out);
                        if (f.verdict() == WallLayers.Verdict.NOT_OPEN_AIR) continue; // caves: not reported
                        faces++;
                        verdicts.merge(f.verdict(), 1, Integer::sum);
                        if (f.verdict() == WallLayers.Verdict.FIELD_NOT_SOLID) {
                            fieldNotSolidBlocks.merge(Compat.blockId(state.getBlock()), 1, Integer::sum);
                        }
                        if (f.verdict() == WallLayers.Verdict.WALL) {
                            int ax = lx + out.getStepX(), az = lz + out.getStepZ();
                            if (ax < 0 || ax > 15 || az < 0 || az > 15) borderWalls++;
                            layerHist[f.layers()]++;
                            int b = (int) Math.floor((f.horizontal() - WallLayers.WALL_MIN_HORIZONTAL) / 0.05);
                            sidewaysHist[Math.max(0, Math.min(5, b))]++;
                        }
                    }
                }
            }
        }

        send(source, "[ALG walls] chunk " + Compat.chunkX(cp) + "," + Compat.chunkZ(cp)
            + ": " + faces + " open-air side faces in the top " + WallLayers.SCAN_DEPTH + " blocks");
        for (Map.Entry<WallLayers.Verdict, Integer> e : verdicts.entrySet()) {
            send(source, String.format("  %-20s %5d  %s", e.getKey(), e.getValue(), e.getKey().description));
        }
        if (!fieldNotSolidBlocks.isEmpty()) {
            StringBuilder fb = new StringBuilder("  FIELD_NOT_SOLID blocks:");
            fieldNotSolidBlocks.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                .forEach(e -> fb.append(' ').append(e.getKey()).append('=').append(e.getValue()));
            send(source, fb.toString());
        }
        if (borderWalls > 0) {
            send(source, "  of the walls, " + borderWalls + " cross the chunk border: generation skips those");
        }
        StringBuilder lh = new StringBuilder("  wall thickness (layers):");
        for (int i = 1; i <= 8; i++) lh.append(' ').append(i).append('=').append(layerHist[i]);
        send(source, lh.toString());
        send(source, String.format("  wall sideways share: .70=%d .75=%d .80=%d .85=%d .90=%d .95+=%d",
            sidewaysHist[0], sidewaysHist[1], sidewaysHist[2], sidewaysHist[3], sidewaysHist[4], sidewaysHist[5]));
        return Command.SINGLE_SUCCESS;
    }

    /** First free Y of a column, as placement reads it (WORLD_SURFACE: anything but air is cover). */
    private static java.util.function.IntBinaryOperator firstFree(ServerLevel level) {
        return (x, z) -> level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    /** The density sampler, or null (reported) when the world has no field to read. */
    private static FractionalSurfaceSampler sampler(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        RandomState randomState = RandomStateHolder.noiseConfigFor(level);
        if (randomState == null || RandomStateHolder.hasRTFRandomState()) {
            send(source, "[ALG wall] Wall layers need vanilla worldgen's density field; this world has none.");
            return null;
        }
        return FractionalSurfaceSampler.create(randomState, level.getChunkSource().getGenerator(), Compat.minY(level));
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}
