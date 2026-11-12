package io.arona74.aronalayersgen.client;

import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * NeoForge, Minecraft 1.21.11: a block model drawn from where it stands, the NeoForge counterpart
 * of a FRAPI model's emitQuads.
 *
 * <p>The chunk renderer asks collectParts(level, pos, state, random) once per block; this runs
 * {@link #emitBlockQuads} into a QuadEmitter and hands back the parts it drew. Without a level
 * (falling blocks, models drawn on their own) the wrapped model's parts are used unchanged.
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
                             List<BlockModelPart> parts) {
        QuadEmitter emitter = new QuadEmitter(level, pos, state, random);
        emitBlockQuads(level, state, pos, emitter);
        parts.addAll(emitter.parts());
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        wrapped.collectParts(random, parts);
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return wrapped.particleIcon();
    }

    @Override
    public TextureAtlasSprite particleIcon(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return wrapped.particleIcon(level, pos, state);
    }
}
