package io.arona74.aronalayersgen.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.TellusCompat;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Reports the Tellus layer backend's per-column data so placement decisions can be
 * inspected against the real world. Everything here comes from
 * {@link TellusCompat#probe}, which runs the same math the injector does.
 *
 * Usage (permission level 2):
 *   /algtellus              - probe the column you are standing on
 *   /algtellus <x> <z>      - probe an explicit column
 *   /algtellus grid [r]     - grid of r blocks around you (default 8, max 24)
 */
public class TellusDebugCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                Commands.literal("algtellus")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> here(ctx))
                    .then(Commands.literal("grid")
                        .executes(ctx -> grid(ctx, 8))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 24))
                            .executes(ctx -> grid(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> at(ctx,
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "z")))))
            )
        );
    }

    private static int here(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (src.getPlayer() == null) {
            send(src, "[ALG] Must be run by a player (or use /algtellus <x> <z>).");
            return 0;
        }
        BlockPos pos = src.getPlayer().blockPosition();
        return at(ctx, pos.getX(), pos.getZ());
    }

    private static int at(CommandContext<CommandSourceStack> ctx, int x, int z) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel world = src.getLevel();
        ChunkGenerator generator = world.getChunkSource().getGenerator();
        LevelChunk chunk = world.getChunk(new ChunkPos(new BlockPos(x, 0, z)).x,
                                          new ChunkPos(new BlockPos(x, 0, z)).z);

        TellusCompat.Probe p = TellusCompat.probe(generator, chunk, x, z);

        send(src, "--- ALG Tellus Column (" + x + ", " + z + ") ---");
        send(src, "config: tellus_inj=" + LayerConfig.TELLUS_LAYER_INJECTION
                + " underwater=" + LayerConfig.UNDERWATER_LAYERS
                + " skip_elev=" + LayerConfig.STRUCTURE_SKIP_ELEVATED
                + " conservative=" + LayerConfig.CONSERVATIVE_SURFACE_HEIGHTMAP);
        if (!p.valid) {
            send(src, "  INVALID: " + p.error);
            return 0;
        }

        send(src, "  elevation=" + fmt(p.elevation) + "m  bathymetry=" + p.bathymetry
                + "  cover=" + p.coverClass + "  submerged=" + p.submerged);
        send(src, "  scaled=" + fmt(p.scaled) + "  cont=" + fmt(p.continuous)
                + "  floor=" + p.floorCont + "  ceil=" + p.ceilCont + "  depth=" + fmt(p.depth));
        send(src, "  tellusSurfaceY(predicted)=" + p.tellusSurfaceY
                + "  naturalTopSolidY=" + p.actualTopSolidY
                + "  rawTopSolidY=" + p.rawTopSolidY
                + "  heightmapY=" + p.heightmapY
                + (p.layersStripped > 0 ? "  (stripped " + p.layersStripped + " existing layer block(s))" : ""));
        send(src, "  MISMATCH=" + p.mismatch
                + (p.mismatch != 0 ? "  <-- DEM disagrees with built terrain" : "  (DEM agrees)"));
        send(src, "  surface=" + p.surfaceBlock + " (mapped=" + p.surfaceMapped + ")  above=" + p.aboveBlock);
        send(src, "  biome=" + p.biome + "  isCold(surfaceY)=" + p.coldAtSurface
                + "  isCold(base<=80)=" + p.coldAtBase
                + "  -> snow-eligible=" + (p.coldAtBase || p.coverClass == 70));
        if (p.snowColumn) {
            send(src, "  SNOW COLUMN: stack (top->down) = " + p.snowStack);
        }
        send(src, "  existingLayer=" + p.existingLayer
                + (p.existingLayerValue > 0 ? " value=" + p.existingLayerValue : "")
                + (p.foundLayerY != Integer.MIN_VALUE ? " @Y=" + p.foundLayerY : ""));

        int expected = p.skippedNoBedData ? 0 : p.stackLayers;
        if (p.skippedNoBedData) {
            send(src, "  DECISION SKIP (submerged, no bathymetry -> no real bed depth; layer suppressed)");
        } else if (p.snowColumn) {
            send(src, "  DECISION snow-layer terrace value=" + p.stackLayers + " on top of the snow surface");
        } else {
            send(src, "  DECISION layer=" + decision(p.stackLayers));
        }

        if (p.snowColumn) {
            // predictedLayerY/MISMATCH are meaningless for snow columns (the reference terrain
            // is the block under the snow, not the snow stack). Report placement directly.
            if (p.stackLayers <= 0) {
                send(src, "  no terrace expected here (depth " + fmt(p.depth) + " -> value 0)");
            } else if (p.foundLayerY != Integer.MIN_VALUE) {
                send(src, "  our snow terrace present @Y=" + p.foundLayerY + " value=" + p.existingLayerValue
                        + (p.existingLayerValue != expected ? "  (expected " + expected + ")" : ""));
            } else {
                // value > 0 but nothing above the snow. A lone snow[8]/snow_block means the terrace
                // never ran here — almost always a chunk generated before this feature existed.
                send(src, "  our snow terrace MISSING (value " + expected + " expected). Stack is a lone "
                        + "snow surface with nothing above -> stale chunk; regenerate this chunk (new coords "
                        + "or delete the region), not just reload.");
            }
        } else {
            send(src, "  predictedLayerY=" + p.predictedLayerY
                    + (p.foundLayerY != Integer.MIN_VALUE
                        ? "  layerPosDelta=" + (p.foundLayerY - p.predictedLayerY)
                        : "  (no layer present)"));
            if (p.foundLayerY != Integer.MIN_VALUE && p.existingLayerValue != expected) {
                send(src, "  !! VALUE MISMATCH: world has " + p.existingLayerValue + " but current logic says "
                        + expected + " -> stale chunk, or conservative_surface fallback overrode it");
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String decision(int layers) {
        return layers == 0
                ? "0 (no layer)"
                : layers + "/8 layer placed above the surface block";
    }

    /**
     * Compact map so a whole slope can be shown at once. Each cell is the replace-mode
     * decision for that column; '#' marks a column whose DEM sample disagrees with the
     * terrain actually built there.
     */
    private static int grid(CommandContext<CommandSourceStack> ctx, int radius) {
        CommandSourceStack src = ctx.getSource();
        if (src.getPlayer() == null) {
            send(src, "[ALG] grid must be run by a player.");
            return 0;
        }
        ServerLevel world = src.getLevel();
        ChunkGenerator generator = world.getChunkSource().getGenerator();
        BlockPos centre = src.getPlayer().blockPosition();
        int cx = centre.getX(), cz = centre.getZ();

        send(src, "--- ALG Tellus Grid  centre=(" + cx + ", " + cz + ")  radius=" + radius + " ---");
        send(src, "cells: 0=no layer, 1-8=layer value, ~=water(no bathymetry, skipped), #=DEM mismatch, .=no data");
        send(src, "rows are Z (north->south), cols are X (west->east)");

        int mismatches = 0, total = 0;
        for (int z = cz - radius; z <= cz + radius; z++) {
            StringBuilder row = new StringBuilder();
            row.append(z == cz ? ">" : " ");
            for (int x = cx - radius; x <= cx + radius; x++) {
                LevelChunk chunk = world.getChunk(new ChunkPos(new BlockPos(x, 0, z)).x,
                                                  new ChunkPos(new BlockPos(x, 0, z)).z);
                TellusCompat.Probe p = TellusCompat.probe(generator, chunk, x, z);
                total++;
                if (!p.valid) {
                    row.append('.');
                } else if (p.skippedNoBedData) {
                    row.append('~');
                } else if (p.mismatch != 0) {
                    row.append('#');
                    mismatches++;
                } else {
                    row.append((char) ('0' + Math.min(9, p.stackLayers)));
                }
            }
            send(src, row.toString());
        }
        send(src, "mismatches=" + mismatches + "/" + total
                + " (" + (total == 0 ? 0 : mismatches * 100 / total) + "%)");
        send(src, "Tip: /algtellus <x> <z> for full detail on any cell above.");
        return Command.SINGLE_SUCCESS;
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}
