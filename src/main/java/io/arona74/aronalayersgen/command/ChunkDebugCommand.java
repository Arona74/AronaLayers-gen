package io.arona74.aronalayersgen.command;

import io.arona74.aronalayersgen.Ids;
import io.arona74.aronalayersgen.Compat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.FractionalSurfaceSampler;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug command that analyses the current chunk and reports what the layer injector
 * sees: surface blocks, ground heights, edge detection, and expected layer counts.
 *
 * <p>The simulated counts call into the injector's own formulas rather than keeping private
 * copies of them. This matters: a divergent copy turns every legitimate difference into an
 * 'M' marker in the presence grid, which reads as a swarm of missing layers that do not
 * exist. When the density path is unavailable the injector places nothing, and so does the
 * simulation.
 *
 * Usage (requires permission level 2):
 *   /algdebug
 */
public class ChunkDebugCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                Commands.literal("algdebug")
                    .requires(src -> Compat.hasPermission(src, 2))
                    .executes(ChunkDebugCommand::execute)
            )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (source.getPlayer() == null) {
            send(source, "[ALG] Must be run by a player.");
            return 0;
        }

        ServerLevel world    = source.getLevel();
        BlockPos   playerPos = source.getPlayer().blockPosition();
        ChunkPos   cp        = Compat.chunkPosOf(playerPos);
        LevelChunk chunk     = world.getChunk(Compat.chunkX(cp), Compat.chunkZ(cp));

        int startX = cp.getMinBlockX();
        int startZ = cp.getMinBlockZ();
        int plx    = playerPos.getX() - startX;   // 0..15
        int plz    = playerPos.getZ() - startZ;   // 0..15
        int bottomY = Compat.minY(chunk);

        // ---- compute ground heights (mirrors VanillaLayerInjector) ----
        int[][] groundHeights = LayerPlacementHelper.computeGroundFloorYs(
                chunk, Heightmap.Types.OCEAN_FLOOR, startX, startZ, bottomY);

        // ---- surface elevations, chunk plus a one-column border ring (mirrors the injector) ----
        // Filled below once the sampler exists; NaN where the field describes no surface.
        float[][] elevations = new float[18][18];

        // ---- resolve whether the injector can run at all, and mirror that ----
        // The fractional-surface backend is the only one for vanilla worldgen. Without a
        // RandomState, or under RTF, there is no density field to read and it places nothing.
        RandomState randomState = RandomStateHolder.noiseConfigFor(world);
        boolean vanillaWorldgen = !RandomStateHolder.hasRTFRandomState();
        boolean fractionalMode  = vanillaWorldgen && randomState != null;

        String simMode = fractionalMode ? "fractional-surface" : "none (injector inactive)";

        FractionalSurfaceSampler fracSampler = fractionalMode
                ? FractionalSurfaceSampler.create(randomState, world.getChunkSource().getGenerator(), bottomY)
                : null;

        if (fractionalMode) {
            for (int i = 0; i < 18; i++) {
                for (int j = 0; j < 18; j++) {
                    int lx = i - 1, lz = j - 1;
                    boolean inside = lx >= 0 && lx < 16 && lz >= 0 && lz < 16;
                    int anchor = inside
                            ? groundHeights[lx][lz] - 1
                            : groundHeights[Math.min(15, Math.max(0, lx))][Math.min(15, Math.max(0, lz))] - 1 + 4;
                    elevations[i][j] = fracSampler.surfaceElevation(startX + lx, startZ + lz, anchor, bottomY);
                }
            }
        }

        // ---- per-column analysis ----
        char[][] topGrid      = new char[16][16];
        char[][] layerGrid    = new char[16][16];
        char[][] fracGrid     = new char[16][16]; // tenths of a block, '-' = no crossing
        char[][] presenceGrid = new char[16][16]; // '#'=has layer, 'M'=sim>0 but missing, '.'=sim=0
        int[][]  layerCounts  = new int[16][16];
        float[][] fractions   = new float[16][16];
        Map<String, Integer> topBlockCounts = new LinkedHashMap<>();
        int wouldPlace = 0;
        int existingLayers = 0;
        int missingLayers = 0;
        int noCrossingColumns = 0;
        int mismatchColumns = 0;
        int flatGradientColumns = 0;
        int[] gradientHist = new int[FractionalSurfaceSampler.GRADIENT_BUCKETS.length + 1];
        int overfullColumns = 0;
        int[] noCrossingRun = new int[FractionalSurfaceSampler.FIELD_RUN_PROBE + 1];

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                // top block = block just below OCEAN_FLOOR height
                int hmY = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR).getFirstAvailable(lx, lz);
                Block topBlock = hmY > bottomY
                        ? chunk.getBlockState(new BlockPos(wx, hmY - 1, wz)).getBlock()
                        : Blocks.AIR;

                char tc; String tn;
                if (topBlock == Blocks.ICE || topBlock == Blocks.FROSTED_ICE
                        || topBlock == Blocks.PACKED_ICE || topBlock == Blocks.BLUE_ICE) { tc = 'I'; tn = Ids.path(Compat.blockId(topBlock)); }
                else if (topBlock == Blocks.WATER)                                  { tc = 'W'; tn = "water"; }
                else if (topBlock == Blocks.POWDER_SNOW)                            { tc = 'P'; tn = "powder_snow"; }
                else if (topBlock == Blocks.SNOW_BLOCK)                             { tc = 'S'; tn = "snow_block"; }
                else if (topBlock == Blocks.SNOW)                                   { tc = 'N'; tn = "snow(layer)"; }
                else if (LayerPlacementHelper.hasMappingFor(topBlock)) {
                    tc = '.'; tn = Ids.path(Compat.blockId(topBlock));
                } else {
                    tc = '?'; tn = "?" + Ids.path(Compat.blockId(topBlock));
                }
                topGrid[lx][lz] = tc;
                topBlockCounts.merge(tn, 1, Integer::sum);

                float fraction = FractionalSurfaceSampler.NO_CROSSING;
                int lc;
                if (fractionalMode) {
                    gradientHist[fracSampler.gradientBucketOf(wx, wz, groundHeights[lx][lz] - 1)]++;
                    // Anchor on the ground scan, not raw OCEAN_FLOOR. The injector samples
                    // during generation when no layers exist yet, so its topSolidY is the real
                    // terrain; by the time this command runs our own layer blocks sit on top and
                    // count toward OCEAN_FLOOR, which would move the sample a block up and make
                    // the command disagree with the run it is supposed to be explaining.
                    // groundHeights already descends past blocks with no ground mapping.
                    fraction = fracSampler.surfaceFraction(wx, wz, groundHeights[lx][lz] - 1, bottomY);
                    float elev = elevations[lx + 1][lz + 1];
                    boolean blockMismatch = !Float.isNaN(elev)
                            && (int) Math.floor(elev) != groundHeights[lx][lz] - 1;
                    if (blockMismatch) {
                        // Field and world disagree about which block is the surface, so the
                        // fraction describes the wrong block. Mirrors the injector.
                        lc = 0;
                        mismatchColumns++;
                    } else if (fraction != FractionalSurfaceSampler.NO_CROSSING) {
                        lc = FractionalSurfaceSampler.layersFromFraction(fraction);
                    } else if (fracSampler.isGradientUnreliable(wx, wz, groundHeights[lx][lz] - 1)) {
                        // Field disagrees with the world and is too flat to locate the surface.
                        // Mirrors the injector.
                        lc = 0;
                        flatGradientColumns++;
                    } else if (fracSampler.isOverfull(wx, wz, groundHeights[lx][lz] - 1)) {
                        // Field overshoots the world's ground by one block: the block is full,
                        // not carved. Mirrors the injector.
                        lc = FractionalSurfaceSampler.fullBlockLayers();
                        overfullColumns++;
                    } else {
                        // Field and world describe different terrain and nothing measurable is
                        // left, so the injector leaves the column bare. Mirrors the injector.
                        noCrossingRun[fracSampler.fieldSolidRunAbove(
                                wx, wz, groundHeights[lx][lz] - 1, FractionalSurfaceSampler.FIELD_RUN_PROBE)]++;
                        lc = 0;
                        noCrossingColumns++;
                    }
                } else {
                    // No density field available: the injector places nothing.
                    lc = 0;
                }

                fractions[lx][lz]   = fraction;
                fracGrid[lx][lz]    = fraction == FractionalSurfaceSampler.NO_CROSSING
                        ? '-' : (char)('0' + Math.min(9, (int)(fraction * 10.0f)));
                layerCounts[lx][lz] = lc;
                layerGrid[lx][lz]   = lc == 0 ? '_' : (char)('0' + Math.min(lc, 9));
                if (lc > 0) wouldPlace++;

                // check if a layer is actually present at the layer placement position
                int gh = groundHeights[lx][lz];
                boolean hasLayer = false;
                if (gh > bottomY) {
                    BlockState atGH = chunk.getBlockState(new BlockPos(wx, gh, wz));
                    hasLayer = hasLayerProperty(atGH);
                    if (hasLayer) existingLayers++;
                }

                if (lc == 0) {
                    presenceGrid[lx][lz] = '.';
                } else {
                    if (hasLayer) {
                        presenceGrid[lx][lz] = '#';
                    } else {
                        presenceGrid[lx][lz] = 'M';
                        missingLayers++;
                    }
                }
            }
        }

        // The injector judges the whole chunk before placing anything: where the field disagrees
        // with the placed world too often, it leaves vanilla terrain. Apply the same verdict here
        // so the grids report the outcome rather than the per-column intent behind it.
        boolean chunkSkipped = fractionalMode
                && (mismatchColumns + overfullColumns + noCrossingColumns) / 256.0
                    > LayerConfig.FRACTIONAL_SURFACE_MAX_DISAGREEMENT;
        if (chunkSkipped) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    layerCounts[lx][lz] = 0;
                    layerGrid[lx][lz] = '_';
                    presenceGrid[lx][lz] = '.';
                }
            }
            wouldPlace = 0;
            missingLayers = 0;
        }

        // ---- rendered surface, and where it jumps ----
        // The count grids describe intent; this is the height a player actually sees, so a
        // tooth shows up here as a column standing above its neighbours no matter which part
        // of the pipeline produced it. groundHeights is the ground block's top face, and the
        // layer stacks on top of that.
        float[][] renderedTop = new float[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                renderedTop[lx][lz] = groundHeights[lx][lz] + layerCounts[lx][lz] / 8.0f;
            }
        }

        // ---- biome at player position ----
        Holder<Biome> biomeEntry = chunk.getNoiseBiome(plx >> 2, playerPos.getY() >> 2, plz >> 2);
        String biomeName = biomeEntry.unwrapKey().isPresent()
                ? Compat.keyId(biomeEntry.unwrapKey().get())
                : "unknown";
        boolean isSnowyBiome = Compat.coldEnoughToSnow(biomeEntry.value(), playerPos);

        // ======== output ========
        String colMarker = buildColMarker(plx);

        send(source, "--- ALG Chunk Debug (cx=" + Compat.chunkX(cp) + " cz=" + Compat.chunkZ(cp) + ") ---");
        send(source, "Config: layer_inj=" + LayerConfig.LAYER_INJECTION
                + " skip_snowy=" + LayerConfig.SKIP_SNOWY_BIOMES
                + " improve_snowy=" + LayerConfig.IMPROVE_SNOWY_BIOMES
                + " underwater=" + LayerConfig.UNDERWATER_LAYERS
                + " mode=" + LayerConfig.INJECTION_MODE
                + " enclosed=" + LayerConfig.ENCLOSED_SPACE_CHECK + "(" + LayerConfig.ENCLOSED_SPACE_HEIGHT + ")"
                + " debug_log=" + LayerConfig.DEBUG_LOGGING);
        send(source, "Config: rtf=" + LayerConfig.RTF_LAYER_INJECTION
                + " struct_inj=" + LayerConfig.STRUCTURE_INJECTION
                + " extra_bounds=" + LayerConfig.STRUCTURE_INJECTION_EXTRA_BOUNDS
                + "(" + LayerConfig.STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE + ")"
                + " skip_elev=" + LayerConfig.STRUCTURE_SKIP_ELEVATED
                + " replace_elev=" + LayerConfig.STRUCTURE_REPLACE_ELEVATED
                + " replace_elev_high=" + LayerConfig.STRUCTURE_REPLACE_ELEVATED_HIGH
                + "(max=" + LayerConfig.STRUCTURE_REPLACE_ELEVATED_HIGH_MAX + ")"
                + " struct_no_layers=" + LayerConfig.STRUCTURE_NO_LAYERS
                + " extra_cleanup=" + LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP
                + "(" + LayerConfig.STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE + ")");
        send(source, "Biome: " + biomeName + " (snowy=" + isSnowyBiome + ")");
        send(source, "Sim path: " + simMode
                + " (reduce=" + LayerConfig.FRACTIONAL_SURFACE_REDUCE_LAYER_COUNT
                + " exactInterp=" + LayerConfig.FRACTIONAL_SURFACE_EXACT_INTERPOLATION
                + " randomState=" + (randomState != null) + ")");
        if (fractionalMode) {
            send(source, "  no-crossing columns (field describes no surface here, left bare)=" + noCrossingColumns + "/256"
                    + (noCrossingColumns > 128 ? "  !! majority had no crossing — density field disagrees with heightmap" : ""));
            send(source, "  block-mismatch columns (field surface in a different block, left bare)=" + mismatchColumns + "/256");
            send(source, "  density tree: " + fracSampler.describeMarkers());
            StringBuilder gh2 = new StringBuilder("  gradient distribution: ");
            for (int i = 0; i <= FractionalSurfaceSampler.GRADIENT_BUCKETS.length; i++) {
                if (i > 0) gh2.append(' ');
                gh2.append(i < FractionalSurfaceSampler.GRADIENT_BUCKETS.length
                            ? "<" + FractionalSurfaceSampler.GRADIENT_BUCKETS[i]
                            : ">=" + FractionalSurfaceSampler.GRADIENT_BUCKETS[FractionalSurfaceSampler.GRADIENT_BUCKETS.length - 1]);
                gh2.append('=').append(gradientHist[i]);
            }
            send(source, gh2.toString());
            int disagreeing = mismatchColumns + overfullColumns + noCrossingColumns;
            double rate = disagreeing / 256.0;
            send(source, String.format("  field/world disagreement=%d/256 (%.0f%%)  threshold %.0f%% -> %s",
                    disagreeing, rate * 100, LayerConfig.FRACTIONAL_SURFACE_MAX_DISAGREEMENT * 100,
                    rate > LayerConfig.FRACTIONAL_SURFACE_MAX_DISAGREEMENT
                            ? "CHUNK SKIPPED, vanilla terrain kept" : "chunk layered"));
            send(source, "  flat-gradient columns (field too flat to locate surface, left to vanilla)="
                    + flatGradientColumns + "/256  (threshold " + LayerConfig.FRACTIONAL_SURFACE_MIN_GRADIENT + ")");
            send(source, "  overfull columns (field surface a block above ground, given full count)=" + overfullColumns + "/256");
            send(source, "  no-crossing solid-run above ground (index=blocks, last=8+): "
                    + java.util.Arrays.toString(noCrossingRun));
        } else {
            send(source, "  !! density path unavailable ("
                    + (randomState == null ? "no RandomState captured" : "RTF worldgen active")
                    + ") — injector places nothing, sim matches");
        }
        if (missingLayers > 0) {
            send(source, "  !! " + missingLayers + " column(s) want a layer but have none.");

            // What is actually sitting where the layer wanted to go, counted over THIS chunk.
            // This is the diagnostic that matters: the not-air guard rejects any occupied
            // position, and an invisible occupant (minecraft:light from datapack worldgen) shows
            // up in no heightmap, no fraction and no grid. A whole mountainside read as healthy
            // while every column was being dropped at the last step.
            Map<String, Integer> blockers = new LinkedHashMap<>();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (presenceGrid[lx][lz] != 'M') continue;
                    int gy = groundHeights[lx][lz];
                    if (gy <= bottomY) continue;
                    BlockState at = chunk.getBlockState(new BlockPos(startX + lx, gy, startZ + lz));
                    if (at.isAir()) continue;
                    blockers.merge(Ids.path(Compat.blockId(at.getBlock())), 1, Integer::sum);
                }
            }
            if (blockers.isEmpty()) {
                send(source, "     placement position is air on all of them — the column was"
                        + " rejected before the write, or the layer was removed afterwards");
            } else {
                StringBuilder sb = new StringBuilder("     placement position occupied by: ");
                boolean first = true;
                for (Map.Entry<String, Integer> e : blockers.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(e.getKey()).append(" x").append(e.getValue());
                    first = false;
                }
                send(source, sb.toString());
                send(source, "     (an occupied position fails the not-air guard; invisible"
                        + " occupants like 'light' are why a column can look perfectly healthy)");
            }

            // Global counters, last. They are reset per injected chunk, so unless this chunk was
            // the most recent one generated they describe some other chunk entirely — they read
            // all-zero for any chunk you walk back to. Kept because they name the guard when they
            // are fresh, but never trust them over the per-column report above.
            send(source, "     last injection pass (MAY BE A DIFFERENT CHUNK): enclosed="
                    + LayerPlacementHelper.debugSkipEnclosed.get()
                    + " notAir=" + LayerPlacementHelper.debugSkipNotAir.get()
                    + " noMapping=" + LayerPlacementHelper.debugSkipNoMapping.get()
                    + " structElev=" + LayerPlacementHelper.debugSkipStructureElevated.get()
                    + " conservSurf=" + LayerPlacementHelper.debugSkipConservativeSurface.get()
                    + " markersReplaced=" + LayerPlacementHelper.debugReplacedMarker.get());
        }
        send(source, "Layers: existing=" + existingLayers + (chunkSkipped ? " (stale, chunk predates the skip)" : "")
                + "  would-place(sim)=" + wouldPlace
                + "/256  missing=" + missingLayers
                + (LayerConfig.RTF_LAYER_INJECTION ? " (RTF mode: sim!=actual)" : ""));

        // top block grid
        send(source, "");
        send(source, "Top Block  I=ice W=water P=psnow S=snowblk N=snow .=mapped ?=no-map");
        send(source, "  " + colMarker);
        for (int lz = 0; lz < 16; lz++) {
            StringBuilder row = new StringBuilder(lz == plz ? ">>" : "  ");
            for (int lx = 0; lx < 16; lx++) row.append(topGrid[lx][lz]);
            send(source, row.toString());
        }

        // sub-block surface fraction grid (fractional mode only)
        if (fractionalMode) {
            send(source, "");
            send(source, "Surface Fraction (tenths of a block, - = no crossing/fallback)");
            send(source, "  Smooth gradients = density field tracking terrain. Blocks of '-' = features or carvers.");
            send(source, "  " + colMarker);
            for (int lz = 0; lz < 16; lz++) {
                StringBuilder row = new StringBuilder(lz == plz ? ">>" : "  ");
                for (int lx = 0; lx < 16; lx++) row.append(fracGrid[lx][lz]);
                send(source, row.toString());
            }
        }

        // layer count grid (sim)
        send(source, "");
        send(source, "Layer Count (sim: " + simMode + ")  (_ = 0)" + (LayerConfig.RTF_LAYER_INJECTION ? " [RTF actual count may differ]" : ""));
        send(source, "  " + colMarker);
        for (int lz = 0; lz < 16; lz++) {
            StringBuilder row = new StringBuilder(lz == plz ? ">>" : "  ");
            for (int lx = 0; lx < 16; lx++) row.append(layerGrid[lx][lz]);
            send(source, row.toString());
        }

        // layer presence grid
        send(source, "");
        send(source, "Layer Presence  #=present M=missing(sim>0,no layer) .=sim=0"
                + (LayerConfig.RTF_LAYER_INJECTION ? " [M may mean RTF gave 0]" : ""));
        send(source, "  " + colMarker);
        for (int lz = 0; lz < 16; lz++) {
            StringBuilder row = new StringBuilder(lz == plz ? ">>" : "  ");
            for (int lx = 0; lx < 16; lx++) row.append(presenceGrid[lx][lz]);
            send(source, row.toString());
        }

        // rendered-surface discontinuities: the teeth, measured rather than inferred
        if (fractionalMode) {
            send(source, "");
            send(source, "Standing columns (rendered top vs lowest 4-neighbour, worst 8, threshold 0.5)");

            // Reported columns are excluded as sources only; their heights stay intact so
            // later ranks still compare against the real surface.
            boolean[][] reportedCol = new boolean[16][16];
            int reported = 0;
            for (int rank = 0; rank < 8; rank++) {
                float bestDrop = 0.5f;
                int bx = -1, bz = -1, nx = -1, nz = -1;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (reportedCol[lx][lz]) continue;
                        for (int[] off : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                            int ax = lx + off[0], az = lz + off[1];
                            if (ax < 0 || ax > 15 || az < 0 || az > 15) continue;
                            float drop = renderedTop[lx][lz] - renderedTop[ax][az];
                            if (drop > bestDrop) { bestDrop = drop; bx = lx; bz = lz; nx = ax; nz = az; }
                        }
                    }
                }
                if (bx < 0) break;
                float f = fractions[bx][bz];
                boolean noCrossing = (f == FractionalSurfaceSampler.NO_CROSSING);
                send(source, String.format("  (%2d,%2d) top=%.3f stands %.3f over (%2d,%2d) top=%.3f | frac=%s raw=%s placed=%d groundTop=%d",
                        bx, bz, renderedTop[bx][bz], bestDrop, nx, nz, renderedTop[nx][nz],
                        noCrossing ? "none" : String.format("%.3f", f),
                        noCrossing ? "-" : String.valueOf(FractionalSurfaceSampler.rawLayers(f)),
                        layerCounts[bx][bz], groundHeights[bx][bz]));
                // Elevation beside the block it was measured in: a crossing block that differs
                // from groundTop-1 is the mismatch that produced standing columns before.
                float e  = elevations[bx + 1][bz + 1];
                float en = elevations[nx + 1][nz + 1];
                if (Float.isNaN(e)) {
                    send(source, String.format("        elev=none (no crossing)  neighbourElev=%s",
                            Float.isNaN(en) ? "none" : String.format("%.3f", en)));
                } else {
                    send(source, String.format("        elev=%.3f (block %d, layer sits on %d%s)  neighbourElev=%s",
                            e, (int) Math.floor(e), groundHeights[bx][bz] - 1,
                            (int) Math.floor(e) == groundHeights[bx][bz] - 1 ? "" : " MISMATCH",
                            Float.isNaN(en) ? "none" : String.format("%.3f", en)));
                }
                reportedCol[bx][bz] = true;
                reported++;
            }
            if (reported == 0) send(source, "  none above 0.5 block — rendered surface is smooth");
        }

        // block type summary
        send(source, "");
        send(source, "Surface block counts (top 8):");
        topBlockCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(8)
                .forEach(e -> send(source, "  " + e.getKey() + " x" + e.getValue()));

        // ---- player column detail ----
        int hmY = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR).getFirstAvailable(plx, plz);
        int wsY = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(plx, plz);
        Block topB   = hmY > bottomY ? chunk.getBlockState(new BlockPos(playerPos.getX(), hmY - 1, playerPos.getZ())).getBlock() : Blocks.AIR;
        int   gh     = groundHeights[plx][plz];
        int   lc     = layerCounts[plx][plz];
        Block groundB = gh > bottomY ? chunk.getBlockState(new BlockPos(playerPos.getX(), gh - 1, playerPos.getZ())).getBlock() : Blocks.AIR;
        Block aboveB  = gh > bottomY ? chunk.getBlockState(new BlockPos(playerPos.getX(), gh,     playerPos.getZ())).getBlock() : Blocks.AIR;

        // Same list as LayerPlacementHelper: snow cannot survive on packed ice, so those columns
        // take the mapping path unconditionally; blue ice only once it has a mapping.
        boolean surfIceOrWater = topB == Blocks.ICE || topB == Blocks.FROSTED_ICE
                || topB == Blocks.PACKED_ICE
                || (topB == Blocks.BLUE_ICE && LayerPlacementHelper.hasMappingFor(Blocks.BLUE_ICE))
                || topB == Blocks.WATER;
        // Anchored on OCEAN_FLOOR, exactly as LayerPlacementHelper does. This used to probe
        // WORLD_SURFACE-1, which on a column under an overhang is the roof several blocks up:
        // a cave mouth with rock overhead reported surfPowderSnow=true with no powder snow
        // anywhere near the ground, and dragged the displayed snow decision down with it.
        boolean surfPowderSnow = topB == Blocks.POWDER_SNOW
                || chunk.getBlockState(new BlockPos(playerPos.getX(), hmY, playerPos.getZ())).getBlock() == Blocks.POWDER_SNOW;
        // Mirrors LayerPlacementHelper: vanilla places snow per position, so its own snow at the
        // placement spot counts as much as a snowy biome does.
        boolean vanillaSnowAtPlacement = chunk.getBlockState(
                new BlockPos(playerPos.getX(), hmY, playerPos.getZ())).getBlock() == Blocks.SNOW;
        boolean wouldUseSnow  = (isSnowyBiome || vanillaSnowAtPlacement)
                && LayerConfig.IMPROVE_SNOWY_BIOMES && !surfPowderSnow && !surfIceOrWater;

        send(source, "");
        send(source, "--- Your Column (lx=" + plx + " lz=" + plz + ") ---");
        send(source, "  OCEAN_FLOOR=" + hmY + "  WORLD_SURFACE=" + wsY);
        send(source, "  topBlock (OCEAN_FLOOR-1): " + Compat.blockId(topB) + " @Y=" + (hmY - 1));
        send(source, "  groundBlock (after mapping scan): " + Compat.blockId(groundB) + " @Y=" + (gh - 1));
        send(source, "  aboveGround (layer placement pos): " + Compat.blockId(aboveB) + " @Y=" + gh);
        send(source, "  groundHeight=" + gh + "  simLayerCount=" + lc
                + (LayerConfig.RTF_LAYER_INJECTION ? " (RTF actual count may differ)" : ""));

        if (fractionalMode) {
            float frac = fractions[plx][plz];
            if (frac != FractionalSurfaceSampler.NO_CROSSING) {
                int topSolidY = gh - 1;   // ground scan, so already-placed layers don't shift it
                // Layers sit on top of the solid block, so the rendered surface is the true
                // surface plus a constant 1-block offset — that offset is what keeps the
                // count continuous across a block step instead of stepping with it.
                send(source, String.format("  density fraction=%.4f  trueSurfaceY=%.4f  renderedSurfaceY=%.4f",
                        frac, topSolidY + frac, topSolidY + 1 + lc / 8.0f));

                int crossingBlock = (int) Math.floor(elevations[plx + 1][plz + 1]);
                if (crossingBlock != gh - 1) {
                    send(source, "  !! field surface sits in block " + crossingBlock
                            + " but the layer would sit on block " + (gh - 1)
                            + " -> fraction measures the wrong block, column left bare");
                }
                send(source, String.format("  vertical gradient=%.4f per block (threshold %.4f)%s",
                        fracSampler.verticalGradientAt(playerPos.getX(), playerPos.getZ(), gh - 1),
                        LayerConfig.FRACTIONAL_SURFACE_MIN_GRADIENT,
                        fracSampler.isGradientUnreliable(playerPos.getX(), playerPos.getZ(), gh - 1)
                                ? "  !! below threshold" : ""));
                int raw = FractionalSurfaceSampler.rawLayers(frac);

                send(source, "  rawCount=" + raw + " -> placed=" + lc
                        + (LayerConfig.FRACTIONAL_SURFACE_REDUCE_LAYER_COUNT
                            ? "  (reduced by one)" : "  (unreduced)"));
                send(source, "  neighbour groundHeights: N=" + neighbourHeight(groundHeights, plx, plz, 0, -1)
                        + " S=" + neighbourHeight(groundHeights, plx, plz, 0, 1)
                        + " W=" + neighbourHeight(groundHeights, plx, plz, -1, 0)
                        + " E=" + neighbourHeight(groundHeights, plx, plz, 1, 0)
                        + "  (center=" + gh + ", -1 = outside chunk, context only)");
            } else {
                boolean overfull = fracSampler.isOverfull(playerPos.getX(), playerPos.getZ(), gh - 1);
                if (overfull) {
                    send(source, String.format("  field density: ground(%d)=%+.4f  above(%d)=%+.4f  above2(%d)=%+.4f",
                            gh - 1, fracSampler.fieldDensityAt(playerPos.getX(), gh - 1, playerPos.getZ()),
                            gh,     fracSampler.fieldDensityAt(playerPos.getX(), gh,     playerPos.getZ()),
                            gh + 1, fracSampler.fieldDensityAt(playerPos.getX(), gh + 1, playerPos.getZ())));
                    send(source, String.format("  vertical gradient=%.4f per block (threshold %.4f)",
                            fracSampler.verticalGradientAt(playerPos.getX(), playerPos.getZ(), gh - 1),
                            LayerConfig.FRACTIONAL_SURFACE_MIN_GRADIENT));
                    send(source, "  density fraction: NO_CROSSING, field overfull by one block");
                    send(source, "     the field calls block " + gh + " solid while the world has no terrain there,");
                    send(source, "     so block " + (gh - 1) + " is completely full -> full count " + lc);
                } else {
                    int run = fracSampler.fieldSolidRunAbove(playerPos.getX(), playerPos.getZ(),
                            gh - 1, FractionalSurfaceSampler.FIELD_RUN_PROBE);
                    send(source, "  density fraction: NO_CROSSING -> fell back to noise-router count");
                    send(source, "     field stays solid " + run + " block(s) above ground"
                            + (run >= FractionalSurfaceSampler.FIELD_RUN_PROBE ? "+ (deep: carver or structure)"
                                                                           : " (shallow: field/world disagreement)"));
                }
            }
        }
        // Exact block stack around the surface. The heightmaps summarise the column and can
        // disagree with what is actually there; this shows the states themselves so a stacked or
        // duplicated layer is visible rather than inferred.
        send(source, "  block stack:");
        for (int y = gh + 3; y >= gh - 2; y--) {
            if (y <= bottomY) continue;
            BlockState st = chunk.getBlockState(new BlockPos(playerPos.getX(), y, playerPos.getZ()));
            String detail = "";
            if (st.hasProperty(BlockStateProperties.LAYERS)) {
                detail = " layers=" + st.getValue(BlockStateProperties.LAYERS);
            }
            send(source, String.format("    Y=%d  %s%s%s", y, Compat.blockId(st.getBlock()), detail,
                    st.blocksMotion() ? "  [blocksMotion]" : ""));
        }
        send(source, "  surfIceOrWater=" + surfIceOrWater + "  surfPowderSnow=" + surfPowderSnow);
        send(source, "  isSnowyBiome=" + isSnowyBiome + "  vanillaSnowAtPlacement=" + vanillaSnowAtPlacement
                + "  wouldUseSnowLayers=" + wouldUseSnow);

        // mapped layer block info — most useful for diagnosing WATERLOGGED failures
        boolean aboveIsWater = aboveB == Blocks.WATER || aboveB == Blocks.ICE || aboveB == Blocks.FROSTED_ICE;
        Block mappedBlock = LayerPlacementHelper.getMappedLayerBlock(groundB, Math.max(lc, 1));
        if (mappedBlock != null) {
            BlockState mappedState = mappedBlock.defaultBlockState();
            boolean supportsWaterlogged = mappedState.hasProperty(BlockStateProperties.WATERLOGGED);
            boolean hasCRLayer = LayerPlacementHelper.getCRLayerProperty(mappedBlock) != null;
            send(source, "  mappedLayerBlock: " + Compat.blockId(mappedBlock)
                    + " (WATERLOGGED=" + supportsWaterlogged + " CRlayer=" + hasCRLayer + ")");
            if (aboveIsWater && !supportsWaterlogged) {
                send(source, "  !! FAIL: above pos has water/ice but mapped block has no WATERLOGGED -> injectLayerAt will skip");
            }
        } else {
            send(source, "  mappedLayerBlock: null (no mapping for " + Compat.blockId(groundB) + ")");
        }

        if (lc == 0) {
            int wN = plz > 0  ? groundHeights[plx][plz-1] : -1;
            int wS = plz < 15 ? groundHeights[plx][plz+1] : -1;
            int wW = plx > 0  ? groundHeights[plx-1][plz] : -1;
            int wE = plx < 15 ? groundHeights[plx+1][plz] : -1;
            send(source, "  -> Sim: NO LAYER (no height edge detected)");
            send(source, "     neighbors: N=" + wN + " S=" + wS + " W=" + wW + " E=" + wE + " (center=" + gh + ")");
        } else {
            send(source, "  -> Sim: LAYER would be placed (count=" + lc + ") at Y=" + gh);
        }

        // ---- structure elevation analysis ----
        if (LayerConfig.STRUCTURE_SKIP_ELEVATED || LayerConfig.RTF_LAYER_INJECTION) {
            send(source, "");
            send(source, "--- Structure Elevation Analysis ---");
            send(source, "  (Note: uses in-chunk neighbor minimum as proxy for natural surface,");
            send(source, "   RTF noise data not available post-generation. Heuristic only.)");

            // Compute min neighbor groundHeight from in-chunk neighbors
            int minNeighborGH = Integer.MAX_VALUE;
            int neighborCount = 0;
            for (int[] off : new int[][]{{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}}) {
                int nx = plx + off[0], nz = plz + off[1];
                if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) {
                    int ngh = groundHeights[nx][nz];
                    if (ngh > bottomY) { minNeighborGH = Math.min(minNeighborGH, ngh); neighborCount++; }
                }
            }

            if (gh > bottomY && minNeighborGH != Integer.MAX_VALUE) {
                // elevDelta: how many blocks above the min neighbor this surface sits
                // gh is exclusive (layer placement Y), so surface block is at gh-1
                // same for minNeighborGH
                int elevDelta = gh - minNeighborGH;
                send(source, "  surfaceY=" + (gh - 1) + "  minNeighborSurfaceY=" + (minNeighborGH - 1)
                        + "  elevDelta=" + elevDelta + "  (in-chunk neighbors=" + neighborCount + ")");

                if (elevDelta > 0) {
                    send(source, "  -> Surface appears elevated by " + elevDelta + " block(s) above min neighbor");
                    boolean spaceAboveIsAir = aboveB == Blocks.AIR;
                    send(source, "  -> Block at layer pos Y=" + gh + ": " + Compat.blockId(aboveB)
                            + " (isAir=" + spaceAboveIsAir + ")");

                    if (LayerConfig.STRUCTURE_SKIP_ELEVATED) {
                        if (elevDelta == 1 && LayerConfig.STRUCTURE_REPLACE_ELEVATED && lc > 0 && spaceAboveIsAir) {
                            send(source, "  -> Sim decision: REPLACE (1-block elev, air above, simLayerCount=" + lc + ")");
                        } else if (elevDelta == 1 && LayerConfig.STRUCTURE_REPLACE_ELEVATED && lc > 0 && !spaceAboveIsAir) {
                            send(source, "  -> Sim decision: SKIP (1-block elev, REPLACE blocked: non-air above)");
                        } else if (elevDelta == 1 && LayerConfig.STRUCTURE_REPLACE_ELEVATED && lc == 0) {
                            send(source, "  -> Sim decision: SKIP (1-block elev, REPLACE blocked: simLayerCount=0)");
                            if (LayerConfig.RTF_LAYER_INJECTION) {
                                send(source, "  !! RTF: if actual layerCount was also 0, REPLACE would also fail in real worldgen");
                            }
                        } else {
                            send(source, "  -> Sim decision: SKIP (elevation=" + elevDelta + ")");
                        }
                    } else {
                        send(source, "  -> skip_elevated=false: no skip/replace applied");
                    }
                } else if (elevDelta < 0) {
                    send(source, "  -> Surface is BELOW min neighbor by " + (-elevDelta) + " block(s) (excavated or terrain dip)");
                } else {
                    send(source, "  -> No elevation detected relative to in-chunk neighbors");
                }
            } else if (gh <= bottomY) {
                send(source, "  -> No valid surface at player column");
            } else {
                send(source, "  -> No in-chunk neighbors available for comparison (player at chunk edge)");
            }

            // RTF-specific diagnosis
            if (LayerConfig.RTF_LAYER_INJECTION) {
                send(source, "");
                send(source, "  RTF mode note:");
                send(source, "  Actual layer count uses RTF terrain noise (floor(height*worldHeight)),");
                send(source, "  completely independent of block state. The sim above uses vanilla edge");
                send(source, "  detection which gives different (usually higher) counts.");
                send(source, "  simLayerCount=" + lc + " -> RTF actual may be 0 at near-integer terrain heights.");
                send(source, "  If RTF layerCount=0: REPLACE cannot fire (requires layerCount>0).");
                send(source, "  If RTF layerCount=0 and sim>0: position shows as 'M' in presence grid.");

                // Specific diagnosis for the case where sim says >0 but no layer exists
                if (lc > 0 && presenceGrid[plx][plz] == 'M') {
                    send(source, "  !! This column is 'M' (sim>0, no layer found). Likely causes:");
                    send(source, "     1. RTF actual layerCount=0 (REPLACE condition fails, terrain barely elevated)");
                    send(source, "     2. structure_skip_elevated triggered (SKIP)");
                    send(source, "     3. No mapping for surface block");
                    send(source, "     4. Layer was placed then removed by structure_no_layers");
                }
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    // ---- helpers that mirror VanillaLayerInjector private logic ----

    private static String buildColMarker(int playerLocalX) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(i == playerLocalX ? 'v' : '-');
        return sb.toString();
    }

    /** Neighbour ground height, or -1 when the offset falls outside this chunk. */
    private static int neighbourHeight(int[][] heights, int lx, int lz, int dx, int dz) {
        int nx = lx + dx;
        int nz = lz + dz;
        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) return -1;
        return heights[nx][nz];
    }

    private static boolean hasLayerProperty(BlockState state) {
        if (state.hasProperty(BlockStateProperties.LAYERS)) return true;
        return LayerPlacementHelper.getCRLayerProperty(state.getBlock()) != null;
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}
