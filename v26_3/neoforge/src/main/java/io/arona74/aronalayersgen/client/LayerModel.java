package io.arona74.aronalayersgen.client;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * NeoForge, Minecraft 26.3: a block model drawn from where it stands, the NeoForge counterpart
 * of a FRAPI model's emitQuads.
 *
 * <p>The chunk renderer asks collectParts(level, pos, state, random) once per block; this runs
 * {@link #emitBlockQuads} into a QuadEmitter and hands back the parts it drew. Without a level
 * (falling blocks, models drawn on their own) the wrapped model's parts are used unchanged.
 *
 * <p>materialFlags (translucent, animated) are the wrapped model's: the quads drawn here are the
 * wrapped model's own, moved, or made with its material and particle sprite.
 */
abstract class LayerModel implements BlockStateModel {

    final BlockStateModel wrapped;

    LayerModel(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    /** Draws the block at {@code pos} into {@code emitter}, as the Fabric model's emitQuads. */
    abstract void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter);

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        QuadEmitter emitter = new QuadEmitter(level, pos, state, random);
        emitBlockQuads(level, state, pos, emitter);
        parts.addAll(emitter.parts());
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
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return wrapped.particleMaterial(level, pos, state);
    }

    @Override
    public int materialFlags() {
        return wrapped.materialFlags();
    }

    @Override
    public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return wrapped.materialFlags(level, pos, state);
    }
}
