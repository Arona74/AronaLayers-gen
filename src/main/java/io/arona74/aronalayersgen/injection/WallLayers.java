package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Wall layers: layer pieces placed against the side of steep terrain, sized from the density field
 * the terrain was generated from.
 *
 * <p>Floor layers measure the surface straight down a column: the zero crossing of finalDensity
 * between the top solid block and the block above it. The field is three-dimensional, so the same
 * crossing exists sideways. For a solid block with open air beside it, the crossing between the two
 * block samples says how far the real surface reaches into the air cell, and that fraction becomes
 * the wall piece's thickness by exactly the floor-layer rule (FractionalSurfaceSampler.layersFromFraction).
 *
 * <p>The field's gradient at the solid block points into the terrain, so its negation is the
 * surface normal. Only faces whose normal points mostly sideways, along the face, are walls; on a
 * terrace or a slope the floor layer above is the right piece and the face is left alone.
 *
 * <p>Vanilla worldgen only (it needs the density field). Wall pieces exist for VanillaLayer+ (its
 * layer blocks take a {@code facing}) and Conquest Reforged ({@code *_vertical_slab}); only
 * VanillaLayer+ is handled so far.
 */
public final class WallLayers {
    private WallLayers() {}

    /**
     * Smallest sideways share of the surface normal for a face to count as a wall: 0.7 is a
     * surface within about 45 degrees of vertical. Tuned from /algdebug wall on real cliffs.
     */
    public static final double WALL_MIN_HORIZONTAL = 0.7;

    /** Why a face does or does not get a wall piece, in the order the checks run. */
    public enum Verdict {
        WALL("wall piece"),
        SOLID_IS_LAYER("the block is itself a layer"),
        CELL_OCCUPIED("the cell beside the face is not air"),
        NOT_OPEN_AIR("the cell beside the face is under cover (cave or overhang)"),
        FLOOR_CELL("the cell beside the face sits on ground: a floor layer belongs there"),
        FIELD_NOT_SOLID("the field calls the solid block air"),
        FIELD_SOLID_IN_AIR("the field calls the air cell solid (carved terrain)"),
        NOT_A_WALL("the surface faces up more than sideways"),
        ZERO_LAYERS("the crossing is too close to the face for a piece"),
        FALLING_MATERIAL("falling material (sand, gravel...) is skipped on walls"),
        NO_MAPPING("no layer mapping for this block"),
        UNSUPPORTED_BACKEND("wall pieces are VanillaLayer+ only for now"),
        NO_FACING("the mapped layer block cannot face sideways");

        public final String description;

        Verdict(String description) {
            this.description = description;
        }
    }

    /**
     * Everything measured for one side face. {@code crossing} is the fraction of the air cell the
     * surface reaches into; {@code nx, ny, nz} the surface normal; {@code horizontal} its share
     * along the face direction. {@code piece} is set only for {@link Verdict#WALL}.
     */
    public record Face(BlockPos solid, Direction out, Verdict verdict,
                       double dSolid, double dAir, float crossing, int rawLayers, int layers,
                       double nx, double ny, double nz, double horizontal,
                       BlockState piece) {}

    /**
     * Measures the face of {@code solid} pointing {@code out} (a horizontal direction).
     * Pure: reads the blocks and the field, places nothing.
     *
     * @param firstFree first free Y of a column, by its world x and z (the WORLD_SURFACE heightmap):
     *                  an air cell below it is covered, a cave or an overhang, not open air
     */
    public static Face analyse(BlockGetter level, IntBinaryOperator firstFree, FractionalSurfaceSampler sampler,
                               BlockPos solid, Direction out) {
        BlockPos air = solid.relative(out);
        int x = solid.getX(), y = solid.getY(), z = solid.getZ();

        // Field readings first, so every verdict carries them for the diagnostic.
        double dSolid = sampler.fieldDensityAt(x, y, z);
        double dAir = sampler.fieldDensityAt(air.getX(), air.getY(), air.getZ());
        double gx = (sampler.fieldDensityAt(x + 1, y, z) - sampler.fieldDensityAt(x - 1, y, z)) * 0.5;
        double gy = (sampler.fieldDensityAt(x, y + 1, z) - sampler.fieldDensityAt(x, y - 1, z)) * 0.5;
        double gz = (sampler.fieldDensityAt(x, y, z + 1) - sampler.fieldDensityAt(x, y, z - 1)) * 0.5;
        double len = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double nx = len > 0 ? -gx / len : 0, ny = len > 0 ? -gy / len : 0, nz = len > 0 ? -gz / len : 0;
        double horizontal = nx * out.getStepX() + nz * out.getStepZ();

        float crossing = -1f;
        int raw = 0, layers = 0;
        if (dSolid > 0 && dAir <= 0) {
            crossing = (float) Math.min(0.99999, dSolid / (dSolid - dAir));
            raw = FractionalSurfaceSampler.rawLayers(crossing);
            layers = FractionalSurfaceSampler.layersFromFraction(crossing);
        }

        Verdict verdict;
        BlockState piece = null;
        BlockState airState = level.getBlockState(air);
        BlockState solidState = level.getBlockState(solid);
        Block solidBlock = solidState.getBlock();
        if (LayerPlacementHelper.hasLayerProperty(solidState)) {
            verdict = Verdict.SOLID_IS_LAYER;
        } else if (!airState.isAir()) {
            verdict = Verdict.CELL_OCCUPIED;
        } else if (air.getY() < firstFree.applyAsInt(air.getX(), air.getZ())) {
            verdict = Verdict.NOT_OPEN_AIR;
        } else if (Compat.blocksMotion(level.getBlockState(air.below()))) {
            verdict = Verdict.FLOOR_CELL;
        } else if (dSolid <= 0) {
            verdict = Verdict.FIELD_NOT_SOLID;
        } else if (dAir > 0) {
            verdict = Verdict.FIELD_SOLID_IN_AIR;
        } else if (horizontal < WALL_MIN_HORIZONTAL) {
            verdict = Verdict.NOT_A_WALL;
        } else if (layers <= 0) {
            verdict = Verdict.ZERO_LAYERS;
        } else if (solidBlock instanceof FallingBlock) {
            verdict = Verdict.FALLING_MATERIAL;
        } else if (LayerPlacementHelper.getBackend() != LayerPlacementHelper.ModBackend.VANILLA_LAYER_PLUS) {
            verdict = Verdict.UNSUPPORTED_BACKEND;
        } else {
            Block layerBlock = LayerPlacementHelper.getMappingRegistry().getLayerBlock(solidBlock, layers);
            if (layerBlock == null) {
                verdict = Verdict.NO_MAPPING;
            } else {
                piece = vanillaLayerPlusWall(layerBlock, out, layers);
                verdict = piece == null ? Verdict.NO_FACING : Verdict.WALL;
            }
        }
        return new Face(solid, out, verdict, dSolid, dAir, crossing, raw, layers, nx, ny, nz, horizontal, piece);
    }

    /**
     * Places wall pieces on every qualifying side face of the chunk, after its floor layers.
     *
     * <p>Only faces whose solid block and air cell both lie inside the chunk: during generation a
     * chunk cannot read or write its neighbours, and a ProtoChunk asked for a block outside itself
     * wraps around to its own far side. Faces on the chunk border are therefore skipped.
     *
     * @return the number of pieces placed
     */
    public static int placeInChunk(ChunkAccess chunk, FractionalSurfaceSampler sampler, Heightmap.Types surfaceType) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int bottomY = Compat.minY(chunk);
        IntBinaryOperator firstFree = (x, z) ->
            chunk.getOrCreateHeightmapUnprimed(surfaceType).getFirstAvailable(x & 15, z & 15);
        Map<Verdict, Integer> tally = LayerConfig.logVanilla() ? new EnumMap<>(Verdict.class) : null;
        int placed = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = startX + lx, z = startZ + lz;
                int top = firstFree.applyAsInt(x, z) - 1;
                for (int y = top; y > Math.max(bottomY, top - SCAN_DEPTH); y--) {
                    BlockPos solid = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(solid);
                    if (!isTerrainCandidate(state)) continue;
                    for (Direction out : Direction.Plane.HORIZONTAL) {
                        int ax = lx + out.getStepX(), az = lz + out.getStepZ();
                        if (ax < 0 || ax > 15 || az < 0 || az > 15) continue;   // chunk border
                        if (!chunk.getBlockState(solid.relative(out)).isAir()) continue;
                        Face f = analyse(chunk, firstFree, sampler, solid, out);
                        if (tally != null) tally.merge(f.verdict(), 1, Integer::sum);
                        if (f.verdict() == Verdict.WALL) {
                            LayerPlacementHelper.setBlockStateSafe(chunk, solid.relative(out), f.piece());
                            placed++;
                        }
                    }
                }
            }
        }
        if (tally != null && !tally.isEmpty()) {
            AronaLayersGen.LOGGER.info("[WallLayers] ChunkAccess {},{}: placed={} verdicts={}",
                Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), placed, tally);
        }
        return placed;
    }

    /** How far below a column's top to look for side faces. */
    public static final int SCAN_DEPTH = 64;

    /** A block a wall piece could stand against: solid terrain, not a tree and not a layer. */
    public static boolean isTerrainCandidate(BlockState state) {
        return Compat.blocksMotion(state)
            && !state.is(net.minecraft.tags.BlockTags.LEAVES)
            && !state.is(net.minecraft.tags.BlockTags.LOGS)
            && !LayerPlacementHelper.hasLayerProperty(state)
            && !LayerPlacementHelper.isCoralBlock(state);
    }

    /**
     * A VanillaLayer+ layer standing against a wall. Its {@code facing} names the side it is
     * attached to, which is the solid block: the opposite of the face direction. (Conquest
     * Reforged's vertical slabs use the other convention, facing away from the wall.)
     *
     * @return null if the block has no sideways facing or layer property
     */
    static BlockState vanillaLayerPlusWall(Block layerBlock, Direction out, int layers) {
        BlockState state = layerBlock.defaultBlockState();
        Property<?> facing = property(state, "facing");
        Property<?> count = property(state, "layers");
        if (facing == null || !(count instanceof IntegerProperty)) return null;
        state = withValue(state, facing, out.getOpposite());
        return state == null ? null : withValue(state, count, layers);
    }

    private static Property<?> property(BlockState state, String name) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(name)) return p;
        }
        return null;
    }

    /** Sets {@code value} if the property accepts it, by matching it among the possible values. */
    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, Object value) {
        for (T possible : property.getPossibleValues()) {
            if (possible.equals(value)) return state.setValue(property, possible);
        }
        return null;
    }
}
