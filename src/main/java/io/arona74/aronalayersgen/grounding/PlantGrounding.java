package io.arona74.aronalayersgen.grounding;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.mixin.ClipContextAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 *   <li>is a column plant (sugar cane, bamboo, cactus, kelp), or</li>
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
        return isOffsetDecoration(block);
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
            || block instanceof KelpBlock || block instanceof KelpPlantBlock;
    }

    private static boolean sameColumn(Block plant, Block below) {
        if (plant instanceof KelpBlock || plant instanceof KelpPlantBlock) {
            return below instanceof KelpPlantBlock;
        }
        if (plant instanceof BambooStalkBlock || plant instanceof BambooSaplingBlock) {
            return below instanceof BambooStalkBlock || below instanceof BambooSaplingBlock;
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

        Block block = state.getBlock();
        BlockPos soil = pos.below();
        BlockGetter soilLevel = level;
        if (isColumnPlant(block)) {
            soilLevel = columnLevel;
            for (int i = 0; i < MAX_COLUMN && sameColumn(block, soilLevel.getBlockState(soil).getBlock()); i++) {
                soil = soil.below();
            }
        } else if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            soil = soil.below();
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

    // ========== Shapes and ray traces ==========

    /** The outline or collision shape of a grounded plant, moved down with it. */
    public static VoxelShape lowerShape(BlockState state, BlockGetter level, BlockPos pos, VoxelShape shape) {
        if (!LayerConfig.PLANT_GROUNDING_SHAPES || shape.isEmpty() || level == null || pos == null) return shape;
        float offset = offset(state, level, pos);
        return offset == 0f ? shape : shape.move(0.0, offset, 0.0);
    }

    /**
     * A lowered plant reaches down into the layer block's space, and the block traversal only asks
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
        if (!LayerConfig.PLANT_GROUNDING || !LayerConfig.PLANT_GROUNDING_SHAPES) return null;
        if (((ClipContextAccessor) context).aronalayersgen$block() != ClipContext.Block.OUTLINE) return null;

        BlockHitResult plantHit = BlockGetter.traverseBlocks(context.getFrom(), context.getTo(), context, (ctx, cell) -> {
            BlockPos plantPos = cell.above();
            BlockState plant = level.getBlockState(plantPos);
            if (!isGrounded(plant) || offset(plant, level, plantPos) == 0f) return null;
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
