package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wall pieces for TerraForged terrain, from VanillaLayer+ 3D layers.
 *
 * <p>TerraForged terrain is a heightfield: the density it generates from has no 3D surface noise,
 * so every column has exactly one surface. A step is the difference between two neighbouring
 * columns, and their surfaces on either side say where the slope between them crosses each block
 * level of that step. For a cell B at level y beside a higher column A, the slope crosses the
 * middle of the level at
 * <pre>t = (sA - (y + 0.5)) / (sA - sB)</pre>
 * of the way from A's centre to B's, and {@code round(t * 8)} becomes the wall's thickness,
 * attached to A. That is the sideways version of the floor rule, where the fraction above the
 * column's whole blocks becomes the layer count.
 *
 * <p>{@code s} is the surface as placed: the column's ground plus its floor layer, in eighths. Not
 * the raw cell height: TerraForged rounds terrain up (its top block always sits at ceil(height))
 * and the floor layer goes on top of that, so the visible surface is about 7/8 of a block above the
 * cell height. Measuring the wall against the cell height put it that much too low and made most
 * walls too thin to place.
 *
 * <p>Which piece goes where:
 * <ul>
 *   <li>a floor layer with a full terrain block beside it becomes a 3D layer (see Layer3D): the
 *       same floor height, merged with a wall toward the higher side. Floor layers on flat ground
 *       stay normal layers, since a 3D layer always has a wall part;</li>
 *   <li>the open cells up the face, to the last full block of A, get a 3D layer of the wall's
 *       material with no floor part ({@code vertical_layers} 0, {@code horizontal_layers} = the
 *       thickness); so does the foot cell of a column without a floor layer. Hard materials only,
 *       the ones VanillaLayer+ gives a 3D layer: soft ones (grass, dirt...) look wrong on edge. A
 *       floor layer under the lowest piece becomes its full block, so the piece does not stand
 *       on a partial floor;</li>
 *   <li>none beside A's own floor layer: a wall piece is full height and would stand above A's
 *       surface.</li>
 * </ul>
 * Only the four side neighbours count. A cell with walls on more than one side puts the piece's
 * open part toward open ground: a side whose opposite neighbour gets no piece at that level wins.
 * Otherwise it faces uphill: the qualifying side the surface rises toward most, from the
 * differences of the four neighbours' surfaces. The thickness is measured at the middle of the part of the cell above its floor layer,
 * so in a floor cell {@code y + 0.5} becomes the middle between the floor's top and the block's
 * top. At the column's ground level the floor part never dips below the downhill neighbour (the
 * one opposite the wall): a higher floor layer there raises it to match. Falling materials (sand,
 * gravel...) are skipped.
 *
 * <p>Only cells inside the chunk get pieces. The wall a border cell leans against lies in the next
 * chunk: with {@code 3D_layer_cross_chunk} on, it is read through the feature step's region (the
 * chunk's eight neighbours, as vanilla features read them; see {@link Margin}); otherwise, or when
 * the region cannot serve that chunk, the side is skipped as {@link SideVerdict#BORDER}.
 *
 * <p>{@link #analyse} is pure and shared with /algdebug wall3d, so the command reports exactly
 * the decision placement makes.
 */
public final class Layer3DWalls {
    private Layer3DWalls() {}

    /**
     * Smallest step, in whole blocks of ground, for a piece. One block: a column whose own floor
     * layer rounds to 0 leaves a one-block step bare otherwise.
     */
    public static final int MIN_STEP = 1;

    /** Why a cell does or does not get a piece, in the order the checks run. */
    public enum CellVerdict {
        PIECE("gets a piece"),
        TROUGH("higher on two opposite sides, or on one with the next chunk opposite: a regular layer, the floor or the thickest wall, whichever is higher"),
        NO_CELL("no TerraForged cell height for this column"),
        BELOW_GROUND("below the column's ground"),
        OCCUPIED("the cell holds something other than air, water or the column's floor layer"),
        FLOOR_FULL("the floor layer is 8 layers: a full block"),
        FLOOR_NO_3D("the floor layer has no 3D variant"),
        STRUCTURE("inside a structure"),
        FLAT("no higher column beside it"),
        NO_WALL("a higher column is beside it, but no side qualifies");

        public final String description;

        CellVerdict(String description) {
            this.description = description;
        }
    }

    /** Why one side neighbour does or does not hold the piece, in the order the checks run. */
    public enum SideVerdict {
        CHOSEN("holds the piece"),
        BEATEN("qualifies, but another side is preferred (faces open ground, or further uphill)"),
        BORDER("in the next chunk: a generating chunk cannot read it"),
        NO_CELL("no cell height"),
        NOT_HIGHER("not higher than this column"),
        STEP_TOO_SMALL("step too small"),
        AT_EDGE_LAYER("this level is beside its floor layer: a wall piece would stand above its surface"),
        NOT_TERRAIN("its block is not terrain (leaves, logs, a layer...)"),
        FALLING("falling material (sand, gravel...)"),
        SOFT_MATERIAL("soft material: wall pieces are hard materials only, those with a 3D layer"),
        TOO_THIN("the wall rounds to 0 layers here"),
        NO_PIECE("no wall layer or 3D variant for the material");

        public final String description;

        SideVerdict(String description) {
            this.description = description;
        }
    }

    /**
     * One side neighbour as measured: {@code hA} its cell height, {@code sA} its surface as placed.
     * {@code t} and {@code thickness} are 0 until computed.
     */
    public record Side(Direction dir, SideVerdict verdict, float hA, float sA, int gA, int minStep,
                       float t, int thickness, BlockState piece) {}

    /**
     * Everything measured for one cell. {@code floorLayers} is 0 for an open cell; {@code piece}
     * is set only for {@link CellVerdict#PIECE}.
     */
    public record Analysis(BlockPos pos, CellVerdict verdict, float hB, float sB, int gB, int floorLayers,
                           List<Side> sides, BlockState piece) {}

    /**
     * The chunk's columns as the pass reads them: cell heights in blocks, ground levels, and the
     * surface as placed (ground plus the floor layer there, in eighths).
     */
    public record Terrain(ChunkAccess chunk, int startX, int startZ, float[][] height, int[][] ground,
                          float[][] surface, Margin margin) {

        /** The chunk alone: border sides stay unread. */
        public static Terrain read(ChunkAccess chunk, int scale, RTFLayerInjector.CellAccessor cells) {
            return read(chunk, scale, cells, null);
        }

        /**
         * The chunk, with the columns beside it in its side neighbours when {@code neighbours} is
         * given and {@code 3D_layer_cross_chunk} is on (see {@link Margin}).
         */
        public static Terrain read(ChunkAccess chunk, int scale, RTFLayerInjector.CellAccessor cells, ChunkLookup neighbours) {
            return read(chunk, scale, cells, neighbours, false);
        }

        /**
         * As above. {@code settled}: the chunk has been through this pass already, so a column
         * whose floor layer was turned into a full block is read a block higher (see settledGround).
         */
        static Terrain read(ChunkAccess chunk, int scale, RTFLayerInjector.CellAccessor cells, ChunkLookup neighbours,
                            boolean settled) {
            int startX = chunk.getPos().getMinBlockX();
            int startZ = chunk.getPos().getMinBlockZ();
            Heightmap.Types hmType = (chunk instanceof LevelChunk)
                ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.OCEAN_FLOOR_WG;
            int[][] ground = LayerPlacementHelper.computeGroundFloorYs(chunk, hmType, startX, startZ, Compat.minY(chunk));
            float[][] height = new float[16][16];
            float[][] surface = new float[16][16];
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    RTFLayerInjector.CellData cell = null;
                    try {
                        cell = cells.getCell(startX + lx, startZ + lz);
                    } catch (Exception ignored) {}
                    height[lx][lz] = cell == null ? Float.NaN : cell.height * scale;
                    int g = settled ? settledGround(chunk, startX + lx, ground[lx][lz], startZ + lz) : ground[lx][lz];
                    ground[lx][lz] = g;
                    surface[lx][lz] = g + floorLayersAt(chunk.getBlockState(new BlockPos(startX + lx, g, startZ + lz))) / 8f;
                }
            }
            Margin margin = neighbours != null && LayerConfig.THREE_D_LAYER_CROSS_CHUNK
                ? Margin.read(startX, startZ, scale, cells, neighbours) : null;
            return new Terrain(chunk, startX, startZ, height, ground, surface, margin);
        }

        /** Whether column {@code (lx, lz)} (chunk-local, -1..16) can be read: inside, or in the margin. */
        public boolean known(int lx, int lz) {
            if (!outside(lx, lz)) return !Float.isNaN(height[lx][lz]);
            return margin != null && margin.known(lx, lz);
        }

        /** True when column {@code (lx, lz)} lies in a neighbouring chunk. */
        public static boolean outside(int lx, int lz) {
            return lx < 0 || lx > 15 || lz < 0 || lz > 15;
        }

        public float heightAt(int lx, int lz) {
            return outside(lx, lz) ? margin.height[lx + 1][lz + 1] : height[lx][lz];
        }

        public int groundAt(int lx, int lz) {
            return outside(lx, lz) ? margin.ground[lx + 1][lz + 1] : ground[lx][lz];
        }

        public float surfaceAt(int lx, int lz) {
            return outside(lx, lz) ? margin.surface[lx + 1][lz + 1] : surface[lx][lz];
        }

        /** The block at {@code pos}: from the chunk, or from the neighbour holding it. */
        public BlockState blockAt(BlockPos pos) {
            int lx = pos.getX() - startX, lz = pos.getZ() - startZ;
            if (!outside(lx, lz) || margin == null) return chunk.getBlockState(pos);
            ChunkAccess neighbour = margin.chunkFor(lx, lz);
            return neighbour == null ? Blocks.AIR.defaultBlockState() : neighbour.getBlockState(pos);
        }

        /**
         * The ground of a column in a chunk that has been through this pass. During generation the
         * heightmap the ground comes from (OCEAN_FLOOR_WG) does not follow block changes, so a column
         * whose floor layer was turned into its full block (promoteFloorsUnderWalls) still reports
         * the old ground, now a solid block: the ground is the cell above it. Elsewhere that cell holds
         * a floor layer, a 3D layer, air or a plant, and the reported ground stands.
         */
        static int settledGround(ChunkAccess chunk, int x, int g, int z) {
            BlockState at = chunk.getBlockState(new BlockPos(x, g, z));
            boolean promoted = isTerrainCandidate(at) && !LayerPlacementHelper.isFloorLayer(at) && !Layer3D.is(at.getBlock());
            return promoted ? g + 1 : g;
        }

        /** The floor height a ground cell holds: its floor layer, or the floor part of a 3D layer. */
        private static int floorLayersAt(BlockState state) {
            if (Layer3D.is(state.getBlock()) || LayerPlacementHelper.isFloorLayer(state)) {
                return LayerPlacementHelper.readLayerCount(state);
            }
            return 0;
        }

        /**
         * Highest ground among the column's side neighbours (at least its own): those inside the
         * chunk, and those in the margin when it is read.
         */
        public int topBeside(int lx, int lz) {
            int top = ground[lx][lz];
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int ax = lx + d.getStepX(), az = lz + d.getStepZ();
                if (!outside(ax, az)) top = Math.max(top, ground[ax][az]);
                else if (known(ax, az)) top = Math.max(top, groundAt(ax, az));
            }
            return top;
        }

        /** The same terrain with one column's floor layer turned into a full block. */
        Terrain raised(int lx, int lz) {
            boolean[][] columns = new boolean[16][16];
            columns[lx][lz] = true;
            return raised(columns);
        }

        /** The same terrain with the marked columns' floor layers turned into full blocks. */
        Terrain raised(boolean[][] columns) {
            int[][] g = new int[16][];
            float[][] s = new float[16][];
            for (int i = 0; i < 16; i++) {
                g[i] = ground[i].clone();
                s[i] = surface[i].clone();
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (!columns[x][z]) continue;
                    g[x][z] += 1;
                    s[x][z] = g[x][z];
                }
            }
            return new Terrain(chunk, startX, startZ, height, g, s, margin);
        }
    }

    /** Finds a chunk by its chunk coordinates, or null when it cannot be read. */
    @FunctionalInterface
    public interface ChunkLookup {
        ChunkAccess get(int chunkX, int chunkZ);
    }

    /**
     * The columns just outside the chunk, in its four side neighbours: the ones a border cell's wall
     * stands in. Indexed {@code [lx + 1][lz + 1]} with chunk-local -1..16; only the edge rows are
     * filled, never the corners (only side neighbours count).
     *
     * <p>A neighbour that generated first is read as it ended up, layers included. One that has not
     * run its own layer pass yet is read raw: its ground (the full blocks) is already final and
     * decides the step and the material, but it has no floor layer yet, so its surface reads up to
     * 7/8 of a block low and a piece against it can come out a layer or two thinner.
     */
    public static final class Margin {
        final float[][] height = new float[18][18];
        final int[][] ground = new int[18][18];
        final float[][] surface = new float[18][18];
        /** The side neighbours, by Direction.get2DDataValue(); null where unreadable. */
        final ChunkAccess[] chunks = new ChunkAccess[4];

        static Margin read(int startX, int startZ, int scale, RTFLayerInjector.CellAccessor cells, ChunkLookup neighbours) {
            Margin m = new Margin();
            for (float[] row : m.height) Arrays.fill(row, Float.NaN);
            int cx = startX >> 4, cz = startZ >> 4;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                ChunkAccess n;
                try {
                    n = neighbours.get(cx + d.getStepX(), cz + d.getStepZ());
                } catch (RuntimeException e) {
                    n = null;
                }
                if (n == null) continue;
                m.chunks[d.get2DDataValue()] = n;
                int nStartX = startX + 16 * d.getStepX(), nStartZ = startZ + 16 * d.getStepZ();
                Heightmap.Types hmType = (n instanceof LevelChunk) ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.OCEAN_FLOOR_WG;
                int[][] g = LayerPlacementHelper.computeGroundFloorYs(n, hmType, nStartX, nStartZ, Compat.minY(n));
                for (int i = 0; i < 16; i++) {
                    // The neighbour's column touching this chunk, and where it sits in the margin.
                    int nlx = d == Direction.EAST ? 0 : d == Direction.WEST ? 15 : i;
                    int nlz = d == Direction.SOUTH ? 0 : d == Direction.NORTH ? 15 : i;
                    int lx = nlx + 16 * d.getStepX(), lz = nlz + 16 * d.getStepZ();
                    RTFLayerInjector.CellData cell = null;
                    try {
                        cell = cells.getCell(nStartX + nlx, nStartZ + nlz);
                    } catch (Exception ignored) {}
                    if (cell == null) continue;
                    // A neighbour that has been layered already may have a promoted column here.
                    int ground = Terrain.settledGround(n, nStartX + nlx, g[nlx][nlz], nStartZ + nlz);
                    m.height[lx + 1][lz + 1] = cell.height * scale;
                    m.ground[lx + 1][lz + 1] = ground;
                    m.surface[lx + 1][lz + 1] = ground
                        + Terrain.floorLayersAt(n.getBlockState(new BlockPos(nStartX + nlx, ground, nStartZ + nlz))) / 8f;
                }
            }
            return m;
        }

        boolean known(int lx, int lz) {
            if (lx < -1 || lx > 16 || lz < -1 || lz > 16) return false;
            boolean edgeX = lx == -1 || lx == 16, edgeZ = lz == -1 || lz == 16;
            if (edgeX == edgeZ) return false;   // inside, or a corner
            return !Float.isNaN(height[lx + 1][lz + 1]);
        }

        ChunkAccess chunkFor(int lx, int lz) {
            Direction d = lx < 0 ? Direction.WEST : lx > 15 ? Direction.EAST : lz < 0 ? Direction.NORTH : Direction.SOUTH;
            return chunks[d.get2DDataValue()];
        }
    }

    /**
     * Places wall pieces in the chunk, after its floor layers.
     *
     * <p>First, a floor layer that would have a wall piece standing on it becomes its full block:
     * the piece is full height, and on a partial floor it would leave a gap under its bottom edge.
     * The piece is then measured from the full block, so the decision is made on the raised column.
     *
     * @param scale  the cell-height to block-Y scale (RTFCompat's world height)
     * @param cells  TerraForged cell data by world x and z
     * @return the number of pieces placed
     */
    public static int placeInChunk(ChunkAccess chunk, int scale, RTFLayerInjector.CellAccessor cells) {
        if (!Layer3D.isActive()) return 0;

        WorldGenLevel region = LayerPlacementHelper.liftRegion();
        Terrain terrain = Terrain.read(chunk, scale, cells, region == null ? null
            : (x, z) -> region.hasChunk(x, z) ? region.getChunk(x, z) : null);
        // Applied to the terrain as read, not read again: during generation the heightmap the
        // ground comes from (OCEAN_FLOOR_WG) does not follow block changes, so a re-read still saw
        // each promoted column a block low and measured its walls from there.
        boolean[][] promotedColumns = new boolean[16][16];
        int promoted = promoteFloorsUnderWalls(terrain, promotedColumns);
        if (promoted > 0) terrain = terrain.raised(promotedColumns);
        int placed = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (Float.isNaN(terrain.height()[lx][lz])) continue;
                int top = terrain.topBeside(lx, lz);
                for (int y = terrain.ground()[lx][lz]; y < top; y++) {
                    Analysis a = analyse(terrain, lx, lz, y, false);
                    if (a.verdict() == CellVerdict.PIECE || a.verdict() == CellVerdict.TROUGH) {
                        LayerPlacementHelper.setBlockStateSafe(chunk, a.pos(), a.piece());
                        if (a.piece().is(Blocks.SNOW)) LayerPlacementHelper.coverGroundWithSnow(chunk, a.pos().below());
                        placed++;
                    }
                }
            }
        }

        int refit = LayerConfig.THREE_D_LAYER_CROSS_CHUNK && region != null ? refitNeighbours(chunk, region, scale, cells) : 0;

        if (LayerConfig.logRtf()) {
            AronaLayersGen.LOGGER.info("[Layer3DWalls] ChunkAccess {},{}: placed={} floorsToFullBlock={} refitInNeighbours={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), placed, promoted, refit);
        }
        return placed;
    }

    /**
     * Re-measures the border row of each side neighbour that has already been through this pass,
     * now that this chunk is final, and writes what changed into it. When that neighbour generated
     * it read this chunk raw, before its layers and full-block floors, so its pieces against this
     * chunk could come out too thin, or be missing where this chunk's ground only reached its final
     * height afterwards. Whichever of two chunks generates second thus leaves both sides of their
     * border measured against each other's final terrain.
     *
     * <p>A neighbour past its feature step is never further than that while this chunk is still in
     * it (lighting needs this chunk's features first), so it is a proto-chunk no player has been
     * sent yet, and the feature step may write into it.
     *
     * @return the number of cells changed in neighbours
     */
    private static int refitNeighbours(ChunkAccess chunk, WorldGenLevel region, int scale, RTFLayerInjector.CellAccessor cells) {
        int cx = Compat.chunkX(chunk.getPos()), cz = Compat.chunkZ(chunk.getPos());
        int changed = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = cx + d.getStepX(), nz = cz + d.getStepZ();
            if (!region.hasChunk(nx, nz)) continue;
            ChunkAccess n = region.getChunk(nx, nz);
            if (!Compat.featuresDone(n)) continue;
            // Only this chunk is read beside it: the row touching it is all that is re-measured.
            Terrain terrain = Terrain.read(n, scale, cells, (x, z) -> x == cx && z == cz ? chunk : null, true);
            Direction back = d.getOpposite();
            for (int i = 0; i < 16; i++) {
                int lx = back == Direction.EAST ? 15 : back == Direction.WEST ? 0 : i;
                int lz = back == Direction.SOUTH ? 15 : back == Direction.NORTH ? 0 : i;
                changed += refitColumn(terrain, lx, lz);
            }
        }
        return changed;
    }

    /**
     * One column of a layered neighbour, measured again: its floor layer turned into the full block
     * when a piece now stands on it (as promoteFloorsUnderWalls does), then a piece written into each
     * cell that now gets a different one. Pieces already there are read as what they replaced, so
     * the decision is the one a fresh pass would make; cells that no longer qualify are left as they
     * are, since the new neighbour only ever adds a wall to lean against.
     */
    private static int refitColumn(Terrain terrain, int lx, int lz) {
        if (Float.isNaN(terrain.height()[lx][lz])) return 0;
        ChunkAccess chunk = terrain.chunk();
        int changed = 0;
        int g = terrain.ground()[lx][lz];
        int top = terrain.topBeside(lx, lz);
        if (top > g + 1) {
            BlockPos floorPos = new BlockPos(terrain.startX() + lx, g, terrain.startZ() + lz);
            BlockState floor = chunk.getBlockState(floorPos);
            Block full = LayerPlacementHelper.isFloorLayer(floor) && !Layer3D.is(floor.getBlock())
                ? LayerPlacementHelper.getMappingRegistry().getSourceBlock(floor.getBlock()) : null;
            if (full != null) {
                CellVerdict above = analyse(terrain.raised(lx, lz), lx, lz, g + 1, true).verdict();
                if (above == CellVerdict.PIECE || above == CellVerdict.TROUGH) {
                    LayerPlacementHelper.setBlockStateSafe(chunk, floorPos, full.defaultBlockState());
                    terrain = terrain.raised(lx, lz);
                    changed++;
                }
            }
        }
        for (int y = terrain.ground()[lx][lz]; y < top; y++) {
            Analysis a = analyse(terrain, lx, lz, y, true);
            if (a.verdict() != CellVerdict.PIECE && a.verdict() != CellVerdict.TROUGH) continue;
            if (chunk.getBlockState(a.pos()).equals(a.piece())) continue;
            LayerPlacementHelper.setBlockStateSafe(chunk, a.pos(), a.piece());
            if (a.piece().is(Blocks.SNOW)) LayerPlacementHelper.coverGroundWithSnow(chunk, a.pos().below());
            changed++;
        }
        return changed;
    }

    /**
     * Turns each floor layer that a wall piece would stand on into its full block (the block the
     * layer was mapped from, e.g. grass_layer -> grass_block). Decided on the raised column: only
     * where the cell above, measured with the full block under it, gets a piece.
     *
     * @param promotedColumns set to true for each column whose floor layer was replaced
     * @return the number of floor layers replaced
     */
    private static int promoteFloorsUnderWalls(Terrain terrain, boolean[][] promotedColumns) {
        ChunkAccess chunk = terrain.chunk();
        int promoted = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (Float.isNaN(terrain.height()[lx][lz])) continue;
                int g = terrain.ground()[lx][lz];
                if (terrain.topBeside(lx, lz) <= g + 1) continue;   // no wall above the floor
                BlockPos floorPos = new BlockPos(terrain.startX() + lx, g, terrain.startZ() + lz);
                BlockState floor = chunk.getBlockState(floorPos);
                if (!LayerPlacementHelper.isFloorLayer(floor) || Layer3D.is(floor.getBlock())) continue;
                Block full = LayerPlacementHelper.getMappingRegistry().getSourceBlock(floor.getBlock());
                if (full == null) continue;
                CellVerdict above = analyse(terrain.raised(lx, lz), lx, lz, g + 1, false).verdict();
                if (above != CellVerdict.PIECE && above != CellVerdict.TROUGH) continue;
                LayerPlacementHelper.setBlockStateSafe(chunk, floorPos, full.defaultBlockState());
                promotedColumns[lx][lz] = true;
                promoted++;
            }
        }
        return promoted;
    }

    /**
     * Measures one cell. Pure: reads the blocks, places nothing.
     *
     * @param asGenerated read pieces this pass already placed as what they replaced (a 3D layer as
     *                    its floor layer, a wall piece as open), so a generated world reports the
     *                    decision that made it; false during placement, which never revisits them
     */
    public static Analysis analyse(Terrain terrain, int lx, int lz, int y, boolean asGenerated) {
        ChunkAccess chunk = terrain.chunk();
        BlockPos pos = new BlockPos(terrain.startX() + lx, y, terrain.startZ() + lz);
        float hB = terrain.height()[lx][lz];
        float sB = terrain.surface()[lx][lz];
        int gB = terrain.ground()[lx][lz];
        List<Side> sides = new ArrayList<>(4);
        if (Float.isNaN(hB)) return new Analysis(pos, CellVerdict.NO_CELL, hB, sB, gB, 0, sides, null);
        if (y < gB) return new Analysis(pos, CellVerdict.BELOW_GROUND, hB, sB, gB, 0, sides, null);

        // What the cell holds decides the piece: open (air or water) gets a wall piece, the floor
        // layer at the foot gets merged with the wall into a 3D layer.
        BlockState here = chunk.getBlockState(pos);
        Block floor3D = null;
        int floorLayers = 0;
        boolean water = here.hasProperty(BlockStateProperties.WATERLOGGED) && here.getValue(BlockStateProperties.WATERLOGGED);
        if (asGenerated && Layer3D.is(here.getBlock()) && Layer3D.verticalLayers(here) > 0) {
            floor3D = here.getBlock();
            floorLayers = Layer3D.verticalLayers(here);
        } else if (here.isAir() || (asGenerated && LayerPlacementHelper.isWallPiece(here))) {
            // open
        } else if (LayerConfig.UNDERWATER_LAYERS && here.is(Blocks.WATER) && here.getFluidState().isSource()) {
            water = true;
        } else if (y == gB && LayerPlacementHelper.isFloorLayer(here)) {
            floorLayers = LayerPlacementHelper.readLayerCount(here);
            if (floorLayers >= 8) return new Analysis(pos, CellVerdict.FLOOR_FULL, hB, sB, gB, floorLayers, sides, null);
            floor3D = Layer3D.forLayer(here.getBlock());
            if (floor3D == null) return new Analysis(pos, CellVerdict.FLOOR_NO_3D, hB, sB, gB, floorLayers, sides, null);
        } else {
            return new Analysis(pos, CellVerdict.OCCUPIED, hB, sB, gB, 0, sides, null);
        }
        if (LayerConfig.STRUCTURE_INJECTION && LayerPlacementHelper.isInsideStructure(chunk, pos)) {
            return new Analysis(pos, CellVerdict.STRUCTURE, hB, sB, gB, floorLayers, sides, null);
        }

        // The piece's open part faces open ground: among the qualifying sides, those whose opposite
        // neighbour stays open at this level come first. Then the wall faces uphill, the side the
        // slope rises toward most. Comparing how far each wall reaches picked the smallest step in
        // a cell boxed in on three sides, where every side reaches all the way across.
        float[] up = uphill(terrain, lx, lz);
        int best = -1;
        boolean bestOpen = false;
        boolean anyHigher = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Side s = side(terrain, pos, lx, lz, y, sB, gB, d, floor3D, floorLayers, water);
            if (s.verdict() != SideVerdict.BORDER && s.verdict() != SideVerdict.NO_CELL
                    && s.verdict() != SideVerdict.NOT_HIGHER) {
                anyHigher = true;
            }
            if (s.verdict() == SideVerdict.CHOSEN) {
                boolean open = opensOnto(terrain, lx, lz, y, d);
                if (best < 0 || (open != bestOpen ? open : uphillOver(s, sides.get(best), up))) {
                    bestOpen = open;
                    if (best >= 0) sides.set(best, beaten(sides.get(best)));
                    best = sides.size();
                } else {
                    s = beaten(s);
                }
            }
            sides.add(s);
        }
        if (best < 0) {
            return new Analysis(pos, anyHigher ? CellVerdict.NO_WALL : CellVerdict.FLAT, hB, sB, gB, floorLayers, sides, null);
        }

        Side chosen = sides.get(best);

        // A trough: higher columns on two opposite sides, at the column's ground level (a side in
        // the next chunk counts as possibly higher, since it cannot be read). A 3D layer
        // leans against one of them only, so the cell gets a regular layer instead, as high as the
        // floor or the thickest wall, whichever is higher. The walls are measured as if the cell
        // had no floor: a later run (or /algdebug) that reads this layer as the floor then reaches
        // the same height instead of raising it again.
        if (y == gB && oppositePair(sides)) {
            int layers = floorLayers;
            for (Side s : sides) {
                if (s.verdict() == SideVerdict.CHOSEN || s.verdict() == SideVerdict.BEATEN) {
                    layers = Math.max(layers, openThickness(s.sA(), y));
                }
            }
            Block base = Layer3D.baseLayerOf(chosen.piece().getBlock());
            // Super Snowy Biomes: every layer in a snowy biome is snow, the trough's too. Not in
            // water, which a snow layer cannot hold.
            if (base != null && !water && LayerConfig.IMPROVE_SNOWY_BIOMES && LayerPlacementHelper.snowyAt(chunk, pos)) {
                base = Blocks.SNOW;
            }
            if (base != null) {
                BlockState layer = LayerPlacementHelper.applyLayerCount(base.defaultBlockState(), base, Math.min(7, layers));
                if (layer.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    layer = layer.setValue(BlockStateProperties.WATERLOGGED, water);
                }
                return new Analysis(pos, CellVerdict.TROUGH, hB, sB, gB, floorLayers, sides, layer);
            }
        }

        // The floor part does not dip below the ground beside it: at the column's ground level, a
        // downhill neighbour (opposite the wall) whose floor layer is higher raises it to match.
        // The wall is then measured again from the raised floor.
        int downhill = y == gB ? downhillFloor(terrain, lx, lz, y, chosen.dir()) : 0;
        if (downhill > floorLayers) {
            floorLayers = Math.min(7, downhill);
            float raisedSurface = gB + floorLayers / 8f;
            float level = (Math.max(y, raisedSurface) + y + 1) * 0.5f;
            float t = (chosen.sA() - level) / (chosen.sA() - raisedSurface);
            int thickness = Math.max(1, Math.min(7, Math.round(t * 8)));
            BlockState piece = Layer3D.state(chosen.piece().getBlock(), floorLayers, thickness, chosen.dir(), water);
            if (piece != null) {
                chosen = new Side(chosen.dir(), SideVerdict.CHOSEN, chosen.hA(), chosen.sA(), chosen.gA(),
                    chosen.minStep(), t, thickness, piece);
                sides.set(best, chosen);
            }
        }
        return new Analysis(pos, CellVerdict.PIECE, hB, sB, gB, floorLayers, sides, chosen.piece());
    }

    /**
     * True if two opposite sides (north and south, or east and west) may both hold a wall: both
     * qualify, or one qualifies and the other lies in the next chunk, which a generating chunk
     * cannot read. A 3D layer there would lean against one side of what may be a trough.
     */
    private static boolean oppositePair(List<Side> sides) {
        int[] state = new int[6];   // 0 = no wall, 1 = in the next chunk, 2 = qualifies
        for (Side s : sides) {
            int i = s.dir().get3DDataValue();
            if (s.verdict() == SideVerdict.CHOSEN || s.verdict() == SideVerdict.BEATEN) state[i] = 2;
            else if (s.verdict() == SideVerdict.BORDER) state[i] = 1;
        }
        return pairs(state[Direction.NORTH.get3DDataValue()], state[Direction.SOUTH.get3DDataValue()])
            || pairs(state[Direction.EAST.get3DDataValue()], state[Direction.WEST.get3DDataValue()]);
    }

    private static boolean pairs(int a, int b) {
        return a > 0 && b > 0 && a + b >= 3;
    }

    /** A wall's thickness at ground level {@code y} measured as if the cell had no floor layer. */
    private static int openThickness(float sA, int y) {
        float t = (sA - (y + 0.5f)) / (sA - y);
        return Math.max(1, Math.min(7, Math.round(t * 8)));
    }

    /**
     * The floor height of the neighbour opposite {@code wallSide}, when its floor sits at level
     * {@code y} (its own floor layer or a 3D layer's floor part); 0 otherwise, or outside the chunk.
     */
    private static int downhillFloor(Terrain terrain, int lx, int lz, int y, Direction wallSide) {
        int nx = lx - wallSide.getStepX(), nz = lz - wallSide.getStepZ();
        if (!terrain.known(nx, nz) || terrain.groundAt(nx, nz) != y) return 0;
        BlockPos pos = new BlockPos(terrain.startX() + nx, y, terrain.startZ() + nz);
        return Terrain.floorLayersAt(terrain.blockAt(pos));
    }

    /**
     * True if a wall on side {@code wallSide} would leave the piece's open part facing open ground:
     * the neighbour opposite the wall is inside the chunk, not above level {@code y}, and gets no
     * piece of its own there (none of its side neighbours rises above {@code y}). A neighbour that
     * is itself a wall piece at this level is not open, even when its ground is level with ours.
     */
    public static boolean opensOnto(Terrain terrain, int lx, int lz, int y, Direction wallSide) {
        int nx = lx - wallSide.getStepX(), nz = lz - wallSide.getStepZ();
        if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || Float.isNaN(terrain.height()[nx][nz])) return false;
        return terrain.ground()[nx][nz] <= y && terrain.topBeside(nx, nz) <= y;
    }

    /**
     * The direction the surface rises around a column: {x, z} differences of its side neighbours'
     * surfaces (east minus west, south minus north). A neighbour outside the chunk or without a
     * cell counts as level with the column.
     */
    public static float[] uphill(Terrain terrain, int lx, int lz) {
        float c = terrain.surface()[lx][lz];
        float east = neighbourSurface(terrain, lx + 1, lz, c), west = neighbourSurface(terrain, lx - 1, lz, c);
        float south = neighbourSurface(terrain, lx, lz + 1, c), north = neighbourSurface(terrain, lx, lz - 1, c);
        return new float[] { east - west, south - north };
    }

    private static float neighbourSurface(Terrain terrain, int lx, int lz, float fallback) {
        return terrain.known(lx, lz) ? terrain.surfaceAt(lx, lz) : fallback;
    }

    /** True if {@code a} points further uphill than {@code b}; the thicker wall breaks a tie. */
    private static boolean uphillOver(Side a, Side b, float[] up) {
        float da = up[0] * a.dir().getStepX() + up[1] * a.dir().getStepZ();
        float db = up[0] * b.dir().getStepX() + up[1] * b.dir().getStepZ();
        return da != db ? da > db : a.t() > b.t();
    }

    private static Side beaten(Side s) {
        return new Side(s.dir(), SideVerdict.BEATEN, s.hA(), s.sA(), s.gA(), s.minStep(), s.t(), s.thickness(), s.piece());
    }

    /** One side neighbour: CHOSEN here means "qualifies"; analyse picks the winner among them. */
    private static Side side(Terrain terrain, BlockPos pos, int lx, int lz, int y, float sB, int gB, Direction d,
                             Block floor3D, int floorLayers, boolean water) {
        int ax = lx + d.getStepX(), az = lz + d.getStepZ();
        if (Terrain.outside(ax, az) && !terrain.known(ax, az)) {
            return new Side(d, SideVerdict.BORDER, Float.NaN, Float.NaN, 0, MIN_STEP, 0f, 0, null);
        }
        float hA = terrain.heightAt(ax, az);
        float sA = terrain.surfaceAt(ax, az);
        int gA = terrain.groundAt(ax, az);
        if (Float.isNaN(hA)) return new Side(d, SideVerdict.NO_CELL, hA, sA, gA, MIN_STEP, 0f, 0, null);
        if (sA <= sB) return new Side(d, SideVerdict.NOT_HIGHER, hA, sA, gA, MIN_STEP, 0f, 0, null);
        if (gA - gB < MIN_STEP) return new Side(d, SideVerdict.STEP_TOO_SMALL, hA, sA, gA, MIN_STEP, 0f, 0, null);
        // y < gA: A is a full block at this level, below its own floor layer.
        if (y >= gA) return new Side(d, SideVerdict.AT_EDGE_LAYER, hA, sA, gA, MIN_STEP, 0f, 0, null);

        BlockState wall = terrain.blockAt(pos.relative(d));
        if (!isTerrainCandidate(wall)) return new Side(d, SideVerdict.NOT_TERRAIN, hA, sA, gA, MIN_STEP, 0f, 0, null);
        if (wall.getBlock() instanceof FallingBlock) return new Side(d, SideVerdict.FALLING, hA, sA, gA, MIN_STEP, 0f, 0, null);

        // Measured at the middle of the part of the cell the wall stands in: above the floor, when
        // the cell holds the column's floor layer (sB lies inside it), else the whole block.
        float level = (Math.max(y, sB) + y + 1) * 0.5f;
        float t = (sA - level) / (sA - sB);
        int thickness = Math.min(7, Math.round(t * 8));
        if (thickness < 1) return new Side(d, SideVerdict.TOO_THIN, hA, sA, gA, MIN_STEP, t, thickness, null);

        // The floor layer's own 3D variant at the foot; otherwise the wall material's, as a wall
        // alone (no floor part).
        Block block = floor3D;
        if (block == null) {
            Block layer = LayerPlacementHelper.hasMappingFor(wall.getBlock())
                ? LayerPlacementHelper.getMappedLayerBlock(wall.getBlock(), thickness) : null;
            if (layer == null) return new Side(d, SideVerdict.NO_PIECE, hA, sA, gA, MIN_STEP, t, thickness, null);
            // Soft materials (grass, dirt, sand...) look wrong standing on edge, and VLP gives 3D
            // layers to its hard ones only.
            block = Layer3D.forLayer(layer);
            if (block == null) return new Side(d, SideVerdict.SOFT_MATERIAL, hA, sA, gA, MIN_STEP, t, thickness, null);
        }
        BlockState piece = Layer3D.state(block, floorLayers, thickness, d, water);
        if (piece == null) return new Side(d, SideVerdict.NO_PIECE, hA, sA, gA, MIN_STEP, t, thickness, null);
        return new Side(d, SideVerdict.CHOSEN, hA, sA, gA, MIN_STEP, t, thickness, piece);
    }

    /** A block a wall piece can stand against: solid terrain, not a tree and not a layer. */
    public static boolean isTerrainCandidate(BlockState state) {
        return Compat.blocksMotion(state)
            && !LayerPlacementHelper.isTreePart(state)
            && !LayerPlacementHelper.hasLayerProperty(state)
            && !LayerPlacementHelper.isCoralBlock(state);
    }
}
