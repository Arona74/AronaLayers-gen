package io.arona74.aronalayersgen.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

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
                CommandManager.literal("algdebug")
                    .requires(src -> src.hasPermissionLevel(2))
                    .executes(ChunkDebugCommand::execute)
            )
        );
    }

    private static int execute(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (source.getPlayer() == null) {
            send(source, "[ALG] Must be run by a player.");
            return 0;
        }

        ServerWorld world    = source.getWorld();
        BlockPos   playerPos = source.getPlayer().getBlockPos();
        ChunkPos   cp        = new ChunkPos(playerPos);
        WorldChunk chunk     = world.getChunk(cp.x, cp.z);

        int startX = cp.getStartX();
        int startZ = cp.getStartZ();
        int plx    = playerPos.getX() - startX;   // 0..15
        int plz    = playerPos.getZ() - startZ;   // 0..15
        int bottomY = chunk.getBottomY();

        // ---- compute ground heights (mirrors VanillaLayerInjector) ----
        int[][] groundHeights = computeGroundHeights(chunk, startX, startZ, bottomY);

        // ---- per-column analysis ----
        char[][] topGrid    = new char[16][16];
        char[][] layerGrid  = new char[16][16];
        int[][]  layerCounts = new int[16][16];
        Map<String, Integer> topBlockCounts = new LinkedHashMap<>();
        int wouldPlace = 0;
        int existingLayers = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                // top block = block just below OCEAN_FLOOR height
                int hmY = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR).get(lx, lz);
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
                    tc = '.'; tn = Registries.BLOCK.getId(topBlock).getPath();
                } else {
                    tc = '?'; tn = "?" + Registries.BLOCK.getId(topBlock).getPath();
                }
                topGrid[lx][lz] = tc;
                topBlockCounts.merge(tn, 1, Integer::sum);

                int lc = calculateLayerCount(groundHeights, lx, lz, bottomY);
                layerCounts[lx][lz] = lc;
                layerGrid[lx][lz]   = lc == 0 ? '_' : (char)('0' + Math.min(lc, 9));
                if (lc > 0) wouldPlace++;

                // count layers already placed at the expected position (groundHeight Y)
                int gh = groundHeights[lx][lz];
                if (gh > bottomY) {
                    BlockState aboveState = chunk.getBlockState(new BlockPos(wx, gh, wz));
                    if (hasLayerProperty(aboveState)) existingLayers++;
                }
            }
        }

        // ---- biome at player position ----
        RegistryEntry<Biome> biomeEntry = chunk.getBiomeForNoiseGen(plx >> 2, playerPos.getY() >> 2, plz >> 2);
        String biomeName = biomeEntry.getKey().isPresent()
                ? biomeEntry.getKey().get().getValue().toString()
                : "unknown";
        boolean isSnowyBiome = biomeEntry.value().isCold(playerPos);

        // ======== output ========
        String colMarker = buildColMarker(plx);

        send(source, "--- ALG Chunk Debug (cx=" + cp.x + " cz=" + cp.z + ") ---");
        send(source, "Config: layer_inj=" + LayerConfig.LAYER_INJECTION
                + " skip_snowy=" + LayerConfig.SKIP_SNOWY_BIOMES
                + " improve_snowy=" + LayerConfig.IMPROVE_SNOWY_BIOMES
                + " underwater=" + LayerConfig.UNDERWATER_LAYERS
                + " mode=" + LayerConfig.INJECTION_MODE
                + " struct_inj=" + LayerConfig.STRUCTURE_INJECTION
                + " enclosed=" + LayerConfig.ENCLOSED_SPACE_CHECK + "(" + LayerConfig.ENCLOSED_SPACE_HEIGHT + ")"
                + " debug_log=" + LayerConfig.DEBUG_LOGGING);
        send(source, "Biome: " + biomeName + " (snowy=" + isSnowyBiome + ")");
        send(source, "Layers: existing=" + existingLayers + "  would-place=" + wouldPlace + "/256");

        // top block grid
        send(source, "");
        send(source, "Top Block  I=ice W=water P=psnow S=snowblk N=snow .=mapped ?=no-map");
        send(source, "  " + colMarker);
        for (int lz = 0; lz < 16; lz++) {
            StringBuilder row = new StringBuilder(lz == plz ? ">>" : "  ");
            for (int lx = 0; lx < 16; lx++) row.append(topGrid[lx][lz]);
            send(source, row.toString());
        }

        // layer count grid
        send(source, "");
        send(source, "Layer Count per column  (_ = 0)");
        send(source, "  " + colMarker);
        for (int lz = 0; lz < 16; lz++) {
            StringBuilder row = new StringBuilder(lz == plz ? ">>" : "  ");
            for (int lx = 0; lx < 16; lx++) row.append(layerGrid[lx][lz]);
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
        int hmY = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR).get(plx, plz);
        int wsY = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(plx, plz);
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
        send(source, "  topBlock (OCEAN_FLOOR-1): " + Registries.BLOCK.getId(topB) + " @Y=" + (hmY - 1));
        send(source, "  groundBlock (after mapping scan): " + Registries.BLOCK.getId(groundB) + " @Y=" + (gh - 1));
        send(source, "  aboveGround (layer placement pos): " + Registries.BLOCK.getId(aboveB) + " @Y=" + gh);
        send(source, "  groundHeight=" + gh + "  layerCount=" + lc);
        send(source, "  surfIceOrWater=" + surfIceOrWater + "  surfPowderSnow=" + surfPowderSnow);
        send(source, "  isSnowyBiome=" + isSnowyBiome + "  wouldUseSnowLayers=" + wouldUseSnow);

        // mapped layer block info — most useful for diagnosing WATERLOGGED failures
        boolean aboveIsWater = aboveB == Blocks.WATER || aboveB == Blocks.ICE || aboveB == Blocks.FROSTED_ICE;
        Block mappedBlock = LayerPlacementHelper.getMappedLayerBlock(groundB, Math.max(lc, 1));
        if (mappedBlock != null) {
            BlockState mappedState = mappedBlock.getDefaultState();
            boolean supportsWaterlogged = mappedState.contains(Properties.WATERLOGGED);
            boolean hasCRLayer = LayerPlacementHelper.getCRLayerProperty(mappedBlock) != null;
            send(source, "  mappedLayerBlock: " + Registries.BLOCK.getId(mappedBlock)
                    + " (WATERLOGGED=" + supportsWaterlogged + " CRlayer=" + hasCRLayer + ")");
            if (aboveIsWater && !supportsWaterlogged) {
                send(source, "  !! FAIL: above pos has water/ice but mapped block has no WATERLOGGED -> injectLayerAt will skip");
            }
        } else {
            send(source, "  mappedLayerBlock: null (no mapping for " + Registries.BLOCK.getId(groundB) + ")");
        }

        if (lc == 0) {
            int wN = plz > 0  ? groundHeights[plx][plz-1] : -1;
            int wS = plz < 15 ? groundHeights[plx][plz+1] : -1;
            int wW = plx > 0  ? groundHeights[plx-1][plz] : -1;
            int wE = plx < 15 ? groundHeights[plx+1][plz] : -1;
            send(source, "  -> NO LAYER (no height edge detected)");
            send(source, "     neighbors: N=" + wN + " S=" + wS + " W=" + wW + " E=" + wE + " (center=" + gh + ")");
        } else {
            send(source, "  -> LAYER would be placed (count=" + lc + ") at Y=" + gh);
        }

        return Command.SINGLE_SUCCESS;
    }

    // ---- helpers that mirror VanillaLayerInjector private logic ----

    private static String buildColMarker(int playerLocalX) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(i == playerLocalX ? 'v' : '-');
        return sb.toString();
    }

    private static int[][] computeGroundHeights(WorldChunk chunk, int startX, int startZ, int bottomY) {
        int[][] heights = new int[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int hmY = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR).get(lx, lz);
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
        if (state.contains(Properties.LAYERS)) return true;
        return LayerPlacementHelper.getCRLayerProperty(state.getBlock()) != null;
    }

    private static void send(ServerCommandSource source, String msg) {
        source.sendFeedback(() -> Text.literal(msg), false);
    }
}
