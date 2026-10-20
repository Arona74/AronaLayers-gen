package io.arona74.aronalayersgen.compat.dh;

import io.arona74.aronalayersgen.block.LayerBlock;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Which blocks Distant Horizons should treat as layers, shared by the whole-block rounding
 * (DistantHorizonsCompat) and the exact-height meshes (DhLayerMesh). Names no DH type.
 */
public final class DhLayers {
    private DhLayers() {}

    /**
     * Height in eighths of a partial floor layer (1-7), or 0 when the state is not one: not a layer
     * block, a wall piece, a full 8-layer block, vanilla snow (handled apart), or a block that merely
     * carries a layers property (Conquest Reforged plants).
     */
    public static int partialEighths(BlockState state) {
        // DH asks for every column it stores and every section it draws: cached per state.
        return PARTIAL.computeIfAbsent(state, DhLayers::computePartialEighths);
    }

    private static final java.util.Map<BlockState, Integer> PARTIAL = new java.util.concurrent.ConcurrentHashMap<>();

    private static int computePartialEighths(BlockState state) {
        if (state.getBlock() == Blocks.SNOW || !LayerPlacementHelper.isFloorLayer(state)) return 0;
        if (!isLayerBlock(state.getBlock())) return 0;
        int count = LayerPlacementHelper.readLayerCount(state);
        return count >= 1 && count <= 7 ? count : 0;
    }

    /** A real layer block: one the mappings place, gen's own, or a *_layer / *_slab with a full block. */
    public static boolean isLayerBlock(Block block) {
        return fullBlockOf(block) != null || LayerPlacementHelper.getMappingRegistry().isLayerBlock(block);
    }

    /** The whole block a layer stands for: its mapping source, gen's own full block, or by name. */
    public static Block fullBlockOf(Block layer) {
        Block source = LayerPlacementHelper.getMappingRegistry().getSourceBlock(layer);
        if (source != null) return source;
        if (layer instanceof LayerBlock gen && gen.getFullBlock() != Blocks.AIR) return gen.getFullBlock();
        return LayerPlacementHelper.getFullBlock(layer);
    }

    /** How the layer looks from afar: its full block, or the layer itself when none is known. */
    public static BlockState appearanceOf(BlockState layer) {
        Block full = fullBlockOf(layer.getBlock());
        return full != null ? full.defaultBlockState() : layer;
    }

    public static boolean isWaterlogged(BlockState state) {
        return state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER);
    }
}
