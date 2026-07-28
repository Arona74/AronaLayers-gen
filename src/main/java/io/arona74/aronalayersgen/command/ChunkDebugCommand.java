package io.arona74.aronalayersgen.command;

import io.arona74.aronalayersgen.Ids;
import io.arona74.aronalayersgen.Compat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
 * Usage (requires permission level 2):
 *   /algdebug
 */
public class ChunkDebugCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                Commands.literal("algdebug")
                    .requires(src -> src.hasPermission(2))
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
        ChunkPos   cp        = new ChunkPos(playerPos);
        LevelChunk chunk     = world.getChunk(cp.x, cp.z);

        int startX = cp.getMinBlockX();
        int startZ = cp.getMinBlockZ();
        int plx    = playerPos.getX() - startX;   // 0..15
        int plz    = playerPos.getZ() - startZ;   // 0..15
        int bottomY = chunk.getMinBuildHeight();

        // ---- compute ground heights (mirrors VanillaLayerInjector) ----
        int[][] groundHeights = computeGroundHeights(chunk, startX, startZ, bottomY);

        // ---- per-column analysis ----
        char[][] topGrid      = new char[16][16];
        char[][] layerGrid    = new char[16][16];
        char[][] presenceGrid = new char[16][16]; // '#'=has layer, 'M'=sim>0 but missing, '.'=sim=0
        int[][]  layerCounts  = new int[16][16];
        Map<String, Integer> topBlockCounts = new LinkedHashMap<>();
        int wouldPlace = 0;
        int existingLayers = 0;
        int missingLayers = 0;

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
                if (topBlock == Blocks.ICE || topBlock == Blocks.FROSTED_ICE)      { tc = 'I'; tn = "ice"; }
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

                int lc = calculateLayerCount(groundHeights, lx, lz, bottomY);
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

        // ---- biome at player position ----
        Holder<Biome> biomeEntry = chunk.getNoiseBiome(plx >> 2, playerPos.getY() >> 2, plz >> 2);
        String biomeName = biomeEntry.unwrapKey().isPresent()
                ? biomeEntry.unwrapKey().get().location().toString()
                : "unknown";
        boolean isSnowyBiome = biomeEntry.value().coldEnoughToSnow(playerPos);

        // ======== output ========
        String colMarker = buildColMarker(plx);

        send(source, "--- ALG Chunk Debug (cx=" + cp.x + " cz=" + cp.z + ") ---");
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
        send(source, "Layers: existing=" + existingLayers + "  would-place(sim)=" + wouldPlace
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

        // layer count grid (vanilla sim)
        send(source, "");
        send(source, "Layer Count (vanilla sim)  (_ = 0)" + (LayerConfig.RTF_LAYER_INJECTION ? " [RTF actual count may differ]" : ""));
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

        boolean surfIceOrWater = topB == Blocks.ICE || topB == Blocks.FROSTED_ICE || topB == Blocks.WATER;
        boolean surfPowderSnow = topB == Blocks.POWDER_SNOW
                || (wsY > bottomY && chunk.getBlockState(new BlockPos(playerPos.getX(), wsY - 1, playerPos.getZ())).getBlock() == Blocks.POWDER_SNOW);
        boolean wouldUseSnow  = isSnowyBiome && LayerConfig.IMPROVE_SNOWY_BIOMES && !surfPowderSnow && !surfIceOrWater;

        send(source, "");
        send(source, "--- Your Column (lx=" + plx + " lz=" + plz + ") ---");
        send(source, "  OCEAN_FLOOR=" + hmY + "  WORLD_SURFACE=" + wsY);
        send(source, "  topBlock (OCEAN_FLOOR-1): " + Compat.blockId(topB) + " @Y=" + (hmY - 1));
        send(source, "  groundBlock (after mapping scan): " + Compat.blockId(groundB) + " @Y=" + (gh - 1));
        send(source, "  aboveGround (layer placement pos): " + Compat.blockId(aboveB) + " @Y=" + gh);
        send(source, "  groundHeight=" + gh + "  simLayerCount=" + lc
                + (LayerConfig.RTF_LAYER_INJECTION ? " (RTF actual count may differ)" : ""));
        send(source, "  surfIceOrWater=" + surfIceOrWater + "  surfPowderSnow=" + surfPowderSnow);
        send(source, "  isSnowyBiome=" + isSnowyBiome + "  wouldUseSnowLayers=" + wouldUseSnow);

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

    private static int[][] computeGroundHeights(LevelChunk chunk, int startX, int startZ, int bottomY) {
        int[][] heights = new int[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int hmY = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR).getFirstAvailable(lx, lz);
                if (hmY <= bottomY) { heights[lx][lz] = bottomY; continue; }

                int wx = startX + lx, wz = startZ + lz;

                // powder_snow is non-opaque; elevate hmY through the full stack
                Block atFloor = chunk.getBlockState(new BlockPos(wx, hmY, wz)).getBlock();
                if (atFloor == Blocks.POWDER_SNOW && LayerPlacementHelper.hasMappingFor(atFloor)) {
                    hmY++;
                    while (chunk.getBlockState(new BlockPos(wx, hmY, wz)).getBlock() == Blocks.POWDER_SNOW) {
                        hmY++;
                    }
                }

                BlockState surfState = chunk.getBlockState(new BlockPos(wx, hmY - 1, wz));
                if (LayerPlacementHelper.hasMappingFor(surfState.getBlock())) {
                    heights[lx][lz] = hmY;
                } else {
                    boolean found = false;
                    for (int dy = 1; dy <= 30; dy++) {
                        int cy = hmY - 1 - dy;
                        if (cy <= bottomY) break;
                        if (LayerPlacementHelper.hasMappingFor(chunk.getBlockState(new BlockPos(wx, cy, wz)).getBlock())) {
                            heights[lx][lz] = cy + 1;
                            found = true;
                            break;
                        }
                    }
                    if (!found) heights[lx][lz] = bottomY;
                }
            }
        }
        return heights;
    }

    private static int calculateLayerCount(int[][] gh, int lx, int lz, int bottomY) {
        int ch = gh[lx][lz];
        if (ch <= bottomY) return 0;
        int maxDrop = 0, lowerCnt = 0, maxRise = 0, higherCnt = 0;
        boolean edge = false;
        for (int[] off : new int[][]{{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}}) {
            int nx = lx + off[0], nz = lz + off[1];
            if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) continue;
            int nh = gh[nx][nz];
            if (nh <= bottomY) continue;
            int diff = ch - nh;
            if (diff > 0)      { edge = true; lowerCnt++;  maxDrop = Math.max(maxDrop, diff);  }
            else if (diff < 0) { edge = true; higherCnt++; maxRise = Math.max(maxRise, -diff); }
        }
        if (!edge) return 0;
        if (higherCnt > 0 && lowerCnt == 0) return layersForBottom(maxRise, higherCnt);
        if (lowerCnt > 0 && higherCnt == 0) return layersForTop(maxDrop, lowerCnt);
        return (layersForTop(maxDrop, lowerCnt) + layersForBottom(maxRise, higherCnt)) / 2;
    }

    private static int layersForBottom(int rise, int cnt) {
        if (rise >= 4) return 7; if (rise >= 3) return 6; if (rise >= 2) return 5;
        if (cnt >= 4) return 5; if (cnt >= 2) return 4; return 3;
    }

    private static int layersForTop(int drop, int cnt) {
        if (drop >= 4) return 1; if (drop >= 3) return 1; if (drop >= 2) return 2;
        if (drop >= 4) return 2; if (cnt >= 2) return 3; return 3;
    }

    private static boolean hasLayerProperty(BlockState state) {
        if (state.hasProperty(BlockStateProperties.LAYERS)) return true;
        return LayerPlacementHelper.getCRLayerProperty(state.getBlock()) != null;
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}
