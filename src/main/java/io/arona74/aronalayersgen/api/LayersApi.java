package io.arona74.aronalayersgen.api;

import io.arona74.aronalayersgen.block.LayerBlock;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What gen knows about layer blocks, for other mods (LSL, Layer Supported LOD, reads it to draw
 * layers in Distant Horizons and Voxy LODs).
 *
 * <p>Gen's own detection covers the configured block mappings, gen's layer blocks, VanillaLayer+
 * 3D layers and Conquest Reforged layers, which a mod looking only at shapes cannot fully tell
 * apart. Callers reach this class by reflection, so gen and its callers never need each other's jar
 * to build: keep the class name and these static signatures stable, and bump {@link #VERSION} when a
 * method is added.
 *
 * <p>Safe to call from any thread once gen has initialised. Nothing here caches: callers do.
 */
public final class LayersApi {
    private LayersApi() {}

    /** API revision. 1: isLayerBlock, hasLayerProperty, isFloorLayer, isWallPiece, layerCount, fullBlockOf. */
    public static final int VERSION = 1;

    /** A real layer block: one the mappings place, gen's own, a 3D layer, or a *_layer / *_slab with a full block. */
    public static boolean isLayerBlock(Block block) {
        return fullBlockOf(block) != null || LayerPlacementHelper.getMappingRegistry().isLayerBlock(block);
    }

    /** Carries a layer property (vanilla {@code layers}, CR {@code layer}, or a 3D layer's). Plants can too. */
    public static boolean hasLayerProperty(BlockState state) {
        return LayerPlacementHelper.hasLayerProperty(state);
    }

    /** A layer lying on the ground (a layer property, not a wall piece). */
    public static boolean isFloorLayer(BlockState state) {
        return LayerPlacementHelper.isFloorLayer(state);
    }

    /** A layer standing against a wall: a 3D layer, or a layer with a horizontal facing. */
    public static boolean isWallPiece(BlockState state) {
        return LayerPlacementHelper.isWallPiece(state);
    }

    /** Layer count in eighths (1-8); CR's 1-4 {@code layer} is converted. 1 when the state has none. */
    public static int layerCount(BlockState state) {
        return LayerPlacementHelper.readLayerCount(state);
    }

    /** The whole block a layer stands for: its mapping source, gen's own full block, or by name. Null if none. */
    public static Block fullBlockOf(Block layer) {
        Block source = LayerPlacementHelper.getMappingRegistry().getSourceBlock(layer);
        if (source != null) return source;
        if (layer instanceof LayerBlock gen && gen.getFullBlock() != Blocks.AIR) return gen.getFullBlock();
        return LayerPlacementHelper.getFullBlock(layer);
    }
}
