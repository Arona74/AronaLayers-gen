package io.arona74.aronalayersgen.client;


import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;
import java.util.function.Predicate;

/**
 * Draws a chorus plant standing on a layer block with its stem reaching down through the layer to
 * the ground under it.
 *
 * <p>Worldgen lifts a whole chorus plant one block so a layer can take its root's cell (see
 * LayerPlacementHelper.liftChorusPlant). The plant keeps its shape, only one block higher; this
 * closes the gap under it with a stem the width of the plant's own arms, from the bottom of the
 * layer's cell up to the bottom of the root block. The part inside the layer is hidden by it.
 *
 * <p>The root keeps its down connection from before the lift, so vanilla still draws the arm at
 * the bottom of its cell. A neighbour update re-reads the connection against the layer and drops
 * it; the extension then covers that arm too.
 */
public class ChorusStemModel implements BlockStateModel {

    /** The stem's sides, inset from the block faces like vanilla's arms (4 to 12 of 16). */
    private static final float STEM_INSET = 4f / 16f;
    /** Top of vanilla's down arm, which the extension meets when the connection is gone. */
    private static final float ARM_TOP = 4f / 16f;

    private final BlockStateModel wrapped;

    public ChorusStemModel(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos,
                          BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        wrapped.emitQuads(emitter, blockView, pos, state, random, cullTest);
        if (!standsOnLayer(blockView, pos)) return;

        boolean armDrawn = state.hasProperty(BlockStateProperties.DOWN) && state.getValue(BlockStateProperties.DOWN);
        for (Direction face : Direction.Plane.HORIZONTAL) {
            stemSide(emitter, face, -1f, 0f);
            if (!armDrawn) stemSide(emitter, face, 0f, ARM_TOP);
        }
    }

    private static boolean standsOnLayer(BlockAndTintGetter blockView, BlockPos pos) {
        return PlantGrounding.isLayerSoil(blockView.getBlockState(pos.below()));
    }

    /**
     * One side of the stem from {@code bottom} to {@code top} (block-relative Y, at most one block
     * apart), with the texture's lower part so segments stack the way vanilla's arms do.
     */
    private void stemSide(QuadEmitter emitter, Direction face, float bottom, float top) {
        emitter.square(face, STEM_INSET, bottom, 1f - STEM_INSET, top, STEM_INSET);
        float vTop = 1f - (top - bottom);
        // square() orders the corners left-top, left-bottom, right-bottom, right-top.
        emitter.uv(0, STEM_INSET, vTop);
        emitter.uv(1, STEM_INSET, 1f);
        emitter.uv(2, 1f - STEM_INSET, 1f);
        emitter.uv(3, 1f - STEM_INSET, vTop);
        emitter.materialBake(wrapped.particleMaterial(), MutableQuadView.BAKE_NORMALIZED);
        emitter.color(-1, -1, -1, -1);
        emitter.emit();
    }

    /** The wrapped geometry, plus whether the stem extension is drawn: it depends on the block below. */
    @Override
    public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos,
                                    BlockState state, RandomSource random) {
        Object wrappedKey = wrapped.createGeometryKey(blockView, pos, state, random);
        if (wrappedKey == null) return null;
        return standsOnLayer(blockView, pos) ? List.of(wrappedKey, "chorus_stem") : wrappedKey;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        wrapped.collectParts(random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
        return wrapped.particleMaterial();
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter blockView, BlockPos pos, BlockState state) {
        return wrapped.particleMaterial(blockView, pos, state);
    }

    @Override
    public int materialFlags() {
        return wrapped.materialFlags();
    }

    @Override
    public int materialFlags(BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random) {
        return wrapped.materialFlags(blockView, pos, state, random);
    }
}
