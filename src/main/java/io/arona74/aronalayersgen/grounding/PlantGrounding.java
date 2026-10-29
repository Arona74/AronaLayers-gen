package io.arona74.aronalayersgen.grounding;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.mixin.ClipContextAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.tags.BlockTags;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plant grounding: a plant standing on a partial-height layer block is lowered onto the layer's
 * surface instead of floating at the top of the block space.
 *
 * <p>Nothing is stored. The offset is recomputed from the block below whenever the plant is drawn
 * (GroundedPlantModel), outlined, collided with (PlantGroundingStateMixin) or ray-traced
 * (PlantGroundingClipMixin), so it applies to generated and hand-placed plants alike and follows
 * the layer when its count changes.
 *
 * <p>Which blocks are plants is detected rather than listed. A block is grounded when it
 * <ul>
 *   <li>extends the plant base class (BushBlock / VegetationBlock, per version), or</li>
 *   <li>is a column plant (sugar cane, bamboo, cactus, kelp, big dripleaf, upward-pointing dripstone), or</li>
 *   <li>has a native random ground offset and no collision, which is what Mojang gives small
 *       ground decoration and what modded plants copy when they don't extend the base class,</li>
 * </ul>
 * and does not already position itself on layers: a {@code layers}/{@code layer} integer property
 * (Conquest Reforged plants carry vanilla's {@code layers}, older CR used {@code layer}) or CR's
 * {@code offset_toggle} custom offsetter. Grounding those would lower them a second time. Lily pads
 * float and hanging states attach from above, so both are skipped. The config lists override all of
 * this by id.
 *
 * <p>The offset only opens when the block below carries a layer property, so a wrongly matched
 * block on ordinary ground, farmland, soul sand or mud never moves.
 */
public final class PlantGrounding {
    private PlantGrounding() {}

    /** Per-state classification results, also used as the duck field values. */
    public static final byte UNKNOWN = 0;
    public static final byte NO = 1;
    public static final byte YES = 2;

    /** Longest same-plant column walked down to find its soil. */
    private static final int MAX_COLUMN = 64;

    private static final Map<Block, Boolean> BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<Block, Boolean> LAYER_SOIL_CACHE = new ConcurrentHashMap<>();
    private static volatile Set<String> forced = Set.of();
    private static volatile Set<String> excluded = Set.of();

    /**
     * Set while a block is being classified. Detection asks the block for its collision shape, which
     * re-enters the shape mixin and from there the classification; the mixin answers "not grounded"
     * without caching while this is set.
     */
    private static final ThreadLocal<Boolean> CLASSIFYING = ThreadLocal.withInitial(() -> false);

    static {
        readLists();
    }

    public static boolean isClassifying() {
        return CLASSIFYING.get();
    }

    /** Re-reads the config lists and drops every cached decision. Models need a resource reload. */
    public static void invalidate() {
        readLists();
        BLOCK_CACHE.clear();
        GroundingFlags.epoch++;
    }

    private static void readLists() {
        forced = new HashSet<>(LayerConfig.PLANT_GROUNDING_FORCE_BLOCKS);
        excluded = new HashSet<>(LayerConfig.PLANT_GROUNDING_EXCLUDE_BLOCKS);
    }

    // ========== Classification ==========

    /** Whether this state is grounded. Fast: reads the per-state cache the mixin keeps. */
    public static boolean isGrounded(BlockState state) {
        return ((GroundedState) state).aronalayersgen$isGrounded();
    }

    /**
     * Full classification of one state; the mixin caches the result on the state. Returns
     * {@link #UNKNOWN} while the block is not registered yet, so nothing is cached against a
     * missing id.
     */
    public static byte classify(BlockState state) {
        if (!LayerConfig.PLANT_GROUNDING) return NO;
        Block block = state.getBlock();
        if (block == Blocks.AIR) return NO;
        String id = Compat.blockId(block);
        if (id == null || id.equals("minecraft:air")) return UNKNOWN;
        if (state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING)) {
            return NO;
        }
        if (isHangingDripstone(state)) return NO;
        return isGroundedBlock(block) ? YES : NO;
    }

    /**
     * Block-level decision, shared by every state of the block. The model plugin uses it to choose
     * which models to wrap; per-state exclusions (hanging) are applied by {@link #offset}.
     */
    public static boolean isGroundedBlock(Block block) {
        if (!LayerConfig.PLANT_GROUNDING) return false;
        Boolean cached = BLOCK_CACHE.get(block);
        if (cached != null) return cached;
        boolean outer = !CLASSIFYING.get();
        if (outer) CLASSIFYING.set(true);
        try {
            boolean grounded = detect(block);
            BLOCK_CACHE.put(block, grounded);
            return grounded;
        } finally {
            if (outer) CLASSIFYING.set(false);
        }
    }

    private static boolean detect(Block block) {
        String id = Compat.blockId(block);
        if (id != null) {
            if (excluded.contains(id)) return false;
            if (forced.contains(id)) return true;
        }
        if (block == Blocks.LILY_PAD) return false;
        if (positionsItself(block)) return false;
        if (Compat.isPlantBase(block) || isColumnPlant(block)) return true;
        if (LayerConfig.GROUND_DECORATIONS && isStandingDecoration(block)) return true;
        return isOffsetDecoration(block);
    }

    /**
     * A free-standing decoration placed on the floor: nothing connects to it, so it can be lowered
     * whole like a plant. Wall torches are the wall-mounted variants of torches and stay put; a
     * hanging lantern is caught per state by {@link #classify}. Block-entity-rendered blocks
     * (chests, signs, skulls, banners, beds) are absent because only the baked model moves.
     */
    private static boolean isStandingDecoration(Block block) {
        if (block instanceof WallTorchBlock || block instanceof RedstoneWallTorchBlock) return false;
        return block instanceof LanternBlock || block instanceof TorchBlock || block instanceof RedstoneTorchBlock
            || block instanceof AbstractCandleBlock || block instanceof FlowerPotBlock || block instanceof CarpetBlock
            || block instanceof BasePressurePlateBlock || block instanceof BrewingStandBlock || block instanceof CakeBlock;
    }

    /** A block that already places itself on layer blocks: grounding it would double the drop. */
    private static boolean positionsItself(Block block) {
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            String name = property.getName();
            if (property instanceof IntegerProperty && (name.equals("layers") || name.equals("layer"))) return true;
            if (property instanceof BooleanProperty && name.equals("offset_toggle")) return true;
        }
        return false;
    }

    /** Native random ground offset and nothing to collide with: small ground decoration. */
    private static boolean isOffsetDecoration(Block block) {
        BlockState state = block.defaultBlockState();
        if (!state.hasOffsetFunction()) return false;
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (Throwable t) {
            // A modded block that cannot answer outside a real level is not worth grounding.
            AronaLayersGen.LOGGER.debug("Plant grounding: skipping {} ({})", Compat.blockId(block), t.toString());
            return false;
        }
    }

    private static boolean isColumnPlant(Block block) {
        return block instanceof SugarCaneBlock || block instanceof CactusBlock
            || block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock
            || block instanceof KelpBlock || block instanceof KelpPlantBlock
            || block instanceof BigDripleafBlock || block instanceof BigDripleafStemBlock
            || block instanceof PointedDripstoneBlock;
    }

    /** A stalactite: pointed dripstone hanging from above, which must not be grounded. */
    private static boolean isHangingDripstone(BlockState state) {
        return state.getBlock() instanceof PointedDripstoneBlock
            && state.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)
            && state.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN;
    }

    private static boolean sameColumn(Block plant, BlockState belowState) {
        Block below = belowState.getBlock();
        if (plant instanceof PointedDripstoneBlock) {
            return below instanceof PointedDripstoneBlock && !isHangingDripstone(belowState);
        }
        if (plant instanceof KelpBlock || plant instanceof KelpPlantBlock) {
            return below instanceof KelpPlantBlock;
        }
        if (plant instanceof BambooStalkBlock || plant instanceof BambooSaplingBlock) {
            return below instanceof BambooStalkBlock || below instanceof BambooSaplingBlock;
        }
        if (plant instanceof BigDripleafBlock || plant instanceof BigDripleafStemBlock) {
            return below instanceof BigDripleafStemBlock;
        }
        return below == plant;
    }

    /** Soil the offset may open on: a layer block, vanilla or Conquest Reforged. */
    public static boolean isLayerSoil(BlockState state) {
        return LAYER_SOIL_CACHE.computeIfAbsent(state.getBlock(), b -> {
            for (Property<?> property : b.getStateDefinition().getProperties()) {
                String name = property.getName();
                if (property instanceof IntegerProperty && (name.equals("layers") || name.equals("layer"))) return true;
            }
            return false;
        });
    }

    // ========== Offset ==========

    /**
     * How far to move this plant down, as a negative block fraction, or 0 when it stays put.
     *
     * <p>The soil is the block under the plant's base: under the lower half of a double plant, under
     * the bottom of a column plant. A grounded plant standing on another grounded plant (a cactus
     * flower on a cactus) takes that plant's offset, so the pair moves together.
     */
    public static float offset(BlockState state, BlockGetter level, BlockPos pos) {
        return offset(state, level, level, pos);
    }

    /**
     * As {@link #offset(BlockState, BlockGetter, BlockPos)}, with a separate view for walking down
     * a column plant.
     *
     * <p>The chunk mesher hands models a copy of one 16-block section plus a 2-block margin, and
     * reads past that margin come back as air. A bamboo or cactus column crossing a section
     * boundary then loses sight of its base from the upper section, so the upper part was drawn
     * unlowered: a cut at the same Y (a section boundary) on every column. The model passes the
     * client level as {@code columnLevel} so the walk can reach the base; single-block and double
     * plants never read further than two blocks down and stay on the mesher's copy.
     */
    public static float offset(BlockState state, BlockGetter level, BlockGetter columnLevel, BlockPos pos) {
        if (!isGrounded(state)) return 0f;
        level = nonBlocking(level, pos);
        columnLevel = nonBlocking(columnLevel, pos);
        if (level == null || columnLevel == null) return 0f;

        Block block = state.getBlock();
        BlockPos soil = pos.below();
        BlockGetter soilLevel = level;
        if (isColumnPlant(block)) {
            soilLevel = columnLevel;
            for (int i = 0; i < MAX_COLUMN && sameColumn(block, soilLevel.getBlockState(soil)); i++) {
                soil = soil.below();
            }
        } else if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            // Walk down through the plant's own blocks: the lower half under an upper half, and the
            // extra lower halves of a stacked plant. Terralith builds "tall" small dripleaf as a
            // column (lower, 0-6 more lowers, upper); stopping one block down left every part above
            // the bottom standing on the plant instead of the layer, drawn a layer-height too high.
            // Such a stack can cross a section boundary, so it walks on the column view too.
            soilLevel = columnLevel;
            for (int i = 0; i < MAX_COLUMN && soilLevel.getBlockState(soil).getBlock() == block; i++) {
                soil = soil.below();
            }
        }

        BlockState soilState = soilLevel.getBlockState(soil);
        if (isGrounded(soilState)) {
            // A plant on a plant (cactus flower on a cactus): the one below may be a column whose
            // base is out of the mesher's reach, so it walks on the column view too.
            return soilState.getBlock() == block ? 0f : offset(soilState, columnLevel, columnLevel, soil);
        }
        if (!isLayerSoil(soilState)) return 0f;
        return surfaceDrop(soilState, soilLevel, soil);
    }

    /**
     * Distance from the top of the soil block's space down to its actual surface, negated.
     *
     * <p>Collision shape first: it is the surface entities stand on, and some modded layer blocks
     * (VanillaLayer+) leave the outline at a full cube while giving the collision the real height.
     * Outline as the fallback, because vanilla snow at layers=1 has no collision at all.
     */
    private static float surfaceDrop(BlockState soil, BlockGetter level, BlockPos pos) {
        VoxelShape shape = soil.getCollisionShape(level, pos);
        if (shape.isEmpty()) shape = soil.getShape(level, pos);
        if (shape.isEmpty()) return 0f;
        double top = shape.max(Direction.Axis.Y);
        if (!Double.isFinite(top) || top <= 0.0 || top >= 1.0) return 0f;
        return -(float) (1.0 - top);
    }

    /**
     * A view of the world that never waits for a chunk.
     *
     * <p>Shapes are asked for from many threads, not just the server thread: Starlight / ScalableLux
     * light chunks on worker threads and query block shapes with the real ServerLevel. Reading the
     * block below a plant through {@code ServerLevel.getBlockState} from such a thread waits for the
     * server thread to provide the chunk, while the server thread waits for that worker: generation
     * stopped dead (seen with Terralith + C2ME). The light engine reads blocks through
     * {@code getChunkForLighting}, which returns only what is already loaded, and so does this.
     * Every read the offset makes stays in the plant's own column, so that one chunk serves them all.
     *
     * @return the level itself, the loaded chunk of {@code pos} on a server level, or null when that
     *         chunk is not loaded (then the plant is simply not lowered)
     */
    private static BlockGetter nonBlocking(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            return server.getChunkSource().getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        }
        return level;
    }

    // ========== Standing trunks ==========

    /**
     * Set while a layer's own top is being measured, so the measurement sees the layer's real
     * collision rather than the full cube {@link #trunkFooting} gives it under a trunk.
     */
    private static final ThreadLocal<Boolean> MEASURING_LAYER = ThreadLocal.withInitial(() -> false);

    /** Per post state, the part of its collision that rests on the ground (see trunkFooting). */
    private static final Map<BlockState, VoxelShape> POST_FOOTINGS = new ConcurrentHashMap<>();

    /**
     * A block a trunk is made of: an upright log, or a huge mushroom's stem.
     */
    public static boolean isTrunkBlock(BlockState state) {
        if (state.getBlock() instanceof RotatedPillarBlock) {
            return state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y && state.is(BlockTags.LOGS);
        }
        return state.is(Blocks.MUSHROOM_STEM);
    }

    /**
     * An upright log (or a huge mushroom's stem) standing on a layer block. TrunkStemModel draws it down to the layer's top,
     * and its outline reaches down with it, so a tree that grew on a layer (or was generated one
     * block higher so its layer could go under it) reads as standing on the layer, not floating
     * over it.
     */
    public static boolean isStandingTrunk(BlockState state, BlockGetter level, BlockPos pos) {
        if (!LayerConfig.treesOnLayers() || !GroundingFlags.ready || level == null || pos == null) return false;
        Block block = state.getBlock();
        if (!(block instanceof RotatedPillarBlock) && block != Blocks.MUSHROOM_STEM) return false;
        BlockGetter view = nonBlocking(level, pos);
        if (view == null || !isLayerSoil(view.getBlockState(pos.below()))) return false;
        // The tag test last: shapes are cached for every state while blocks register, before any
        // tag is bound, and 26.x throws on an unbound tag where older versions answer false.
        return isTrunkBlock(state);
    }

    /**
     * A fence, wall, pane or iron bars: a post that connects to its neighbours. Fence gates are left
     * out: a gate stands off the ground by design and is not drawn down.
     */
    public static boolean isPostBlock(Block block) {
        return block instanceof FenceBlock || block instanceof WallBlock
            || block instanceof IronBarsBlock;
    }

    /**
     * A post standing on a layer block. It cannot be lowered (its rails would no longer meet a
     * neighbour that is not on a layer), so like a trunk it is drawn down to the layer instead, and
     * its outline and the layer's collision take its footprint.
     */
    public static boolean isStandingPost(BlockState state, BlockGetter level, BlockPos pos) {
        if (!LayerConfig.postsOnLayers() || LayerConfig.postsLowered()) return false;
        if (!GroundingFlags.ready || level == null || pos == null) return false;
        if (!isPostBlock(state.getBlock())) return false;
        BlockGetter view = nonBlocking(level, pos);
        return view != null && isLayerSoil(view.getBlockState(pos.below()));
    }

    // ----- Posts joining across a step -----
    //
    // Vanilla joins posts only on the same level. A wall on a layer is drawn down into its layer's
    // cell, so a wall next to that layer one level lower looks as if it should meet it, but neither
    // has a side towards the other. These add the missing sides at draw and collision time, without
    // touching the block states: the lower post gets a side towards the layer cell ("level"
    // bridges), the standing post gets one in its extension, at the lower post's level ("step"
    // bridges). Both are bit masks over Direction.get2DDataValue().

    /** Whether two posts join the way vanilla joins them side by side. */
    public static boolean sameFamily(BlockState a, BlockState b) {
        Block x = a.getBlock();
        Block y = b.getBlock();
        if (x instanceof WallBlock) return y instanceof WallBlock;
        if (x instanceof IronBarsBlock) return y instanceof IronBarsBlock;
        if (x instanceof FenceBlock) {
            return y instanceof FenceBlock && a.is(BlockTags.WOODEN_FENCES) == b.is(BlockTags.WOODEN_FENCES);
        }
        return false;
    }

    private static EnumProperty<WallSide> wallSide(Direction dir) {
        return switch (dir) {
            case NORTH -> BlockStateProperties.NORTH_WALL;
            case SOUTH -> BlockStateProperties.SOUTH_WALL;
            case WEST -> BlockStateProperties.WEST_WALL;
            default -> BlockStateProperties.EAST_WALL;
        };
    }

    /** A fence's or pane's side towards {@code dir} (CrossCollisionBlock's own map is protected). */
    private static BooleanProperty crossSide(Direction dir) {
        return switch (dir) {
            case NORTH -> BlockStateProperties.NORTH;
            case SOUTH -> BlockStateProperties.SOUTH;
            case WEST -> BlockStateProperties.WEST;
            default -> BlockStateProperties.EAST;
        };
    }

    /** Whether a post already has its side towards {@code dir}. Anything without sides answers true. */
    private static boolean connected(BlockState post, Direction dir) {
        if (post.getBlock() instanceof WallBlock) return post.getValue(wallSide(dir)) != WallSide.NONE;
        if (post.getBlock() instanceof CrossCollisionBlock) return post.getValue(crossSide(dir));
        return true;
    }

    /** The post with its sides towards every direction in {@code mask} added (low, for a wall). */
    public static BlockState joined(BlockState post, int mask) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if ((mask & (1 << dir.get2DDataValue())) == 0) continue;
            if (post.getBlock() instanceof WallBlock) {
                post = post.setValue(wallSide(dir), WallSide.LOW);
            } else if (post.getBlock() instanceof CrossCollisionBlock) {
                post = post.setValue(crossSide(dir), true);
            }
        }
        return post;
    }

    /**
     * The post as it looks with another post stacked on it: a wall's low sides become tall, as
     * vanilla makes them under a wall, so a copy of the post continues into the next one without a
     * notch. Sides in {@code keepLow} stay low (a step side, which meets a low side).
     */
    public static BlockState stacked(BlockState post, int keepLow) {
        if (!(post.getBlock() instanceof WallBlock)) return post;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if ((keepLow & (1 << dir.get2DDataValue())) != 0) continue;
            if (post.getValue(wallSide(dir)) == WallSide.LOW) post = post.setValue(wallSide(dir), WallSide.TALL);
        }
        return post;
    }

    /**
     * Whether a post on a layer shows at the level below it, where a post beside its layer stands:
     * drawn down to the layer (EXTEND), or lowered at least halfway into that level (LOWER, a layer
     * of 4 or less), so the two read as neighbours and are joined.
     */
    private static boolean showsBelow(BlockState post, BlockGetter level, BlockPos pos) {
        if (!LayerConfig.postsLowered()) return isStandingPost(post, level, pos);
        return postDrop(post, level, pos) <= -0.5f;
    }

    /**
     * Sides a post needs towards a standing post of its family one level up: the cell beside it is
     * that post's layer, which the post above is drawn down into.
     */
    public static int levelBridges(BlockState post, BlockGetter level, BlockPos pos) {
        if (!LayerConfig.postsOnLayers() || !GroundingFlags.ready || level == null || pos == null) return 0;
        if (!isPostBlock(post.getBlock())) return 0;
        BlockGetter view = nonBlocking(level, pos);
        if (view == null) return 0;
        int mask = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (connected(post, dir)) continue;
            BlockPos side = pos.relative(dir);
            if (!isLayerSoil(view.getBlockState(side))) continue;
            BlockPos abovePos = side.above();
            BlockState above = view.getBlockState(abovePos);
            if (sameFamily(post, above) && showsBelow(above, level, abovePos)) mask |= 1 << dir.get2DDataValue();
        }
        return mask;
    }

    /**
     * Sides a standing post needs in its extension, towards a post of its family beside its layer:
     * the lower post of {@link #levelBridges}, seen from above.
     */
    public static int stepBridges(BlockState post, BlockGetter level, BlockPos pos) {
        if (!showsBelow(post, level, pos)) return 0;
        BlockGetter view = nonBlocking(level, pos);
        if (view == null) return 0;
        BlockPos layer = pos.below();
        int mask = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (connected(post, dir)) continue;
            if (sameFamily(post, view.getBlockState(layer.relative(dir)))) mask |= 1 << dir.get2DDataValue();
        }
        return mask;
    }

    // ----- Lowered posts (PostMode.LOWER) -----

    /**
     * How far a post is lowered onto the layer under it, as a negative block fraction, or 0. The
     * soil is under the bottom of its column of posts, so a wall stacked on a lowered wall is
     * lowered with it.
     */
    public static float postDrop(BlockState state, BlockGetter level, BlockPos pos) {
        return postDrop(state, level, level, pos);
    }

    /** As {@link #postDrop(BlockState, BlockGetter, BlockPos)}, walking the column on {@code columnLevel} (see offset). */
    public static float postDrop(BlockState state, BlockGetter level, BlockGetter columnLevel, BlockPos pos) {
        if (!LayerConfig.postsLowered() || !GroundingFlags.ready || level == null || pos == null) return 0f;
        if (!isPostBlock(state.getBlock())) return 0f;
        BlockGetter view = nonBlocking(level, pos);
        BlockGetter column = nonBlocking(columnLevel, pos);
        if (view == null || column == null) return 0f;
        BlockPos soil = pos.below();
        BlockGetter soilLevel = view;
        if (isPostBlock(view.getBlockState(soil).getBlock())) {
            soilLevel = column;
            for (int i = 0; i < MAX_COLUMN && isPostBlock(soilLevel.getBlockState(soil).getBlock()); i++) {
                soil = soil.below();
            }
        }
        BlockState soilState = soilLevel.getBlockState(soil);
        if (!isLayerSoil(soilState)) return 0f;
        return surfaceDrop(soilState, soilLevel, soil);
    }

    /**
     * Whether a lowered post reaches back up to a block standing on it: lowering opens a gap under
     * that block, which the parts of the post reaching its cell top close again. A post stacked on
     * it is lowered with the column and needs nothing.
     */
    public static boolean reachesUp(BlockState post, BlockGetter level, BlockPos pos, float drop) {
        if (!LayerConfig.LOWERED_POSTS_REACH_UP || drop == 0f || level == null || pos == null) return false;
        BlockGetter view = nonBlocking(level, pos);
        if (view == null) return false;
        BlockPos abovePos = pos.above();
        BlockState above = view.getBlockState(abovePos);
        if (above.isAir()) return false;
        return !isPostBlock(above.getBlock()) || postDrop(above, level, abovePos) != drop;
    }

    /** A lowered post's shape: moved down, and reaching back up to a block on it (see reachesUp). */
    private static VoxelShape lowered(BlockState post, BlockGetter level, BlockPos pos, VoxelShape shape, float drop) {
        VoxelShape moved = shape.move(0.0, drop, 0.0);
        if (!reachesUp(post, level, pos, drop)) return moved;
        VoxelShape[] out = {moved};
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            if (y1 > 1.0 - 1.0E-4) out[0] = Shapes.or(out[0], Shapes.box(x0, Math.max(0.0, y0 + drop), z0, x1, 1.0, z1));
        });
        return out[0];
    }

    /**
     * Sides of a post that join a block lowered by a different amount: a post with one of the two on
     * a layer, or both on layers of different counts, or anything that is never lowered (a fence
     * gate, a full block). The faces at the end of each side are culled as if the two still met,
     * which leaves the end open: these sides have their end faces drawn.
     */
    public static int openSides(BlockState post, BlockGetter level, BlockPos pos, float drop) {
        if (!LayerConfig.postsLowered() || !isPostBlock(post.getBlock()) || level == null || pos == null) return 0;
        BlockGetter view = nonBlocking(level, pos);
        if (view == null) return 0;
        int mask = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!connected(post, dir)) continue;
            BlockPos sidePos = pos.relative(dir);
            BlockState side = view.getBlockState(sidePos);
            // Any block it joins, not only posts: a fence gate is not lowered (drop 0), and the end
            // of a lowered fence's rails would show its inside against it. Against a full block
            // the end face is drawn flush with that block and stays out of sight.
            if (postDrop(side, level, sidePos) != drop) mask |= 1 << dir.get2DDataValue();
        }
        return mask;
    }

    /** A post's collision moved down with it (lowered), or with its level bridges added (extended). */
    public static VoxelShape bridgedCollision(BlockState post, BlockGetter level, BlockPos pos, VoxelShape shape) {
        float drop = postDrop(post, level, pos);
        if (drop != 0f) {
            int sides = levelBridges(post, level, pos) | stepBridges(post, level, pos);
            if (sides != 0) shape = Shapes.or(shape, joined(post, sides).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            return lowered(post, level, pos, shape, drop);
        }
        int mask = levelBridges(post, level, pos);
        if (mask == 0) return shape;
        return Shapes.or(shape, joined(post, mask).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    /** A standing trunk or post: drawn, outlined and footed down to the layer under it. */
    public static boolean reachesDown(BlockState state, BlockGetter level, BlockPos pos) {
        return isStandingTrunk(state, level, pos) || isStandingPost(state, level, pos);
    }

    /**
     * The columns of a shape that rest on the bottom of its cell, stretched to the full cell
     * height: a fence's post (its rails start higher), a wall's post and sides.
     */
    private static VoxelShape footprint(VoxelShape shape) {
        VoxelShape[] out = {Shapes.empty()};
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            if (y0 < 1.0E-4) out[0] = Shapes.or(out[0], Shapes.box(x0, 0.0, z0, x1, 1.0, z1));
        });
        return out[0];
    }

    /**
     * Height of the layer block at {@code layerPos} as a fraction of its cell, from its collision
     * shape (VanillaLayer+ leaves the outline a full cube), or 1 when it cannot be read.
     */
    public static float layerTop(BlockGetter level, BlockPos layerPos) {
        BlockGetter view = nonBlocking(level, layerPos);
        if (view == null) return 1f;
        BlockState layer = view.getBlockState(layerPos);
        MEASURING_LAYER.set(true);
        try {
            VoxelShape shape = layer.getCollisionShape(view, layerPos);
            if (shape.isEmpty()) shape = layer.getShape(view, layerPos);
            if (shape.isEmpty()) return 0f;
            double top = shape.max(Direction.Axis.Y);
            return Double.isFinite(top) ? (float) Math.min(1.0, Math.max(0.0, top)) : 1f;
        } finally {
            MEASURING_LAYER.set(false);
        }
    }

    /**
     * A standing trunk's or post's outline, reaching down to the top of its layer: the whole cell for
     * a trunk, the part of the outline that rests on the ground for a post.
     */
    public static VoxelShape standingOutline(BlockState state, BlockGetter level, BlockPos pos, VoxelShape shape) {
        if (shape.isEmpty()) return shape;
        float drop = postDrop(state, level, pos);
        if (drop != 0f) {
            int sides = levelBridges(state, level, pos) | stepBridges(state, level, pos);
            if (sides != 0) shape = Shapes.or(shape, joined(state, sides).getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            return lowered(state, level, pos, shape, drop);
        }
        int bridges = levelBridges(state, level, pos);
        if (bridges != 0) shape = Shapes.or(shape, joined(state, bridges).getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        boolean trunk = isStandingTrunk(state, level, pos);
        if (!trunk && !isStandingPost(state, level, pos)) return shape;
        float top = layerTop(level, pos.below());
        if (top >= 1f) return shape;
        VoxelShape reach = Shapes.box(0.0, top - 1.0, 0.0, 1.0, 0.0, 1.0);
        if (!trunk) {
            int step = stepBridges(state, level, pos);
            VoxelShape base = step == 0 ? shape : joined(state, step).getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            reach = Shapes.join(footprint(base).move(0.0, -1.0, 0.0), reach, BooleanOp.AND);
        }
        return Shapes.or(shape, reach);
    }

    /**
     * The collision of a layer block with a standing trunk on it: a full cube. The part of the cell
     * above the layer is drawn as trunk; without this a chicken or a dropped item could walk into
     * the foot of the tree. A player is too tall to fit there anyway.
     *
     * <p>Under a standing post the layer takes the post's footprint instead (a fence's post and
     * rails), which keeps animals in a
     * pen built on low layers: a chicken fits under a fence 7/8 of a block above the ground.
     */
    public static VoxelShape trunkFooting(BlockGetter level, BlockPos pos, VoxelShape shape) {
        if ((!LayerConfig.treesOnLayers() && !LayerConfig.postsOnLayers())
                || level == null || pos == null || MEASURING_LAYER.get()) return shape;
        BlockGetter view = nonBlocking(level, pos);
        if (view == null) return shape;
        BlockPos above = pos.above();
        BlockState aboveState = view.getBlockState(above);
        if (isStandingTrunk(aboveState, level, above)) return Shapes.block();
        if (!isStandingPost(aboveState, level, above)) return shape;
        // With its step bridges, so the side reaching the lower post is footed too.
        aboveState = joined(aboveState, stepBridges(aboveState, level, above));
        // Vanilla posts' collision depends on the state alone, and this runs on every collision
        // query against the layer, so the footprint is computed once per state.
        VoxelShape foot = POST_FOOTINGS.computeIfAbsent(aboveState,
            s -> footprint(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        return foot.isEmpty() ? shape : Shapes.or(shape, foot);
    }

    // ========== Shapes and ray traces ==========

    /** The outline or collision shape of a grounded plant, moved down with it. */
    public static VoxelShape lowerShape(BlockState state, BlockGetter level, BlockPos pos, VoxelShape shape) {
        if (!LayerConfig.PLANT_GROUNDING_SHAPES || shape.isEmpty() || level == null || pos == null) return shape;
        float offset = offset(state, level, pos);
        return offset == 0f ? shape : shape.move(0.0, offset, 0.0);
    }

    /**
     * A lowered plant (or a standing trunk) reaches down into the layer block's space, and the block traversal only asks
     * each cell about its own block: a look ray that crosses only the layer cell would pass through
     * the plant's lower part without hitting it. Re-trace for grounded plants sitting above each
     * cell the ray crosses, and keep that hit when it is nearer than the original.
     *
     * <p>Outline rays only (block picking); collision and visual rays are left alone, which keeps
     * this off the mob line-of-sight path.
     *
     * @return a nearer hit, or null to keep the original
     */
    public static BlockHitResult clipLowered(BlockGetter level, ClipContext context, BlockHitResult original) {
        boolean plants = LayerConfig.PLANT_GROUNDING && LayerConfig.PLANT_GROUNDING_SHAPES;
        if (!plants && !LayerConfig.treesOnLayers() && !LayerConfig.postsOnLayers()) return null;
        if (((ClipContextAccessor) context).aronalayersgen$block() != ClipContext.Block.OUTLINE) return null;

        BlockHitResult plantHit = BlockGetter.traverseBlocks(context.getFrom(), context.getTo(), context, (ctx, cell) -> {
            BlockPos plantPos = cell.above();
            BlockGetter view = nonBlocking(level, plantPos);
            if (view == null) return null;
            BlockState plant = view.getBlockState(plantPos);
            // A standing trunk or post reaches into the layer cell the same way (see standingOutline).
            boolean reaches = plants && isGrounded(plant) && offset(plant, level, plantPos) != 0f;
            if (!reaches && !reachesDown(plant, level, plantPos) && postDrop(plant, level, plantPos) == 0f) return null;
            return ctx.getBlockShape(plant, level, plantPos).clip(ctx.getFrom(), ctx.getTo(), plantPos);
        }, ctx -> null);

        if (plantHit == null || plantHit.getType() == HitResult.Type.MISS) return null;
        if (original != null && original.getType() != HitResult.Type.MISS
                && original.getLocation().distanceToSqr(context.getFrom())
                    <= plantHit.getLocation().distanceToSqr(context.getFrom())) {
            return null;
        }
        return plantHit;
    }
}
