package io.arona74.aronalayersgen.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.util.List;

/**
 * NeoForge, Minecraft 1.21.1: a block model drawn from where it stands, the NeoForge counterpart of a
 * FRAPI model's emitBlockQuads.
 *
 * <p>The chunk renderer asks getModelData(level, pos, state) for every block, then getQuads once per
 * side and render type with what it returned. getModelData stores the level and position; the
 * first getQuads of a block and render type runs {@link #emitBlockQuads} into a QuadEmitter, and
 * each side is then answered from it. Anywhere else (falling blocks, the break overlay, models
 * drawn without a level) the wrapped model is drawn unchanged.
 */
abstract class LayerModel extends BakedModelWrapper<BakedModel> {

    private record Context(BlockAndTintGetter level, BlockPos pos) {}

    private static final ModelProperty<Context> CONTEXT = new ModelProperty<>();

    /** The quads built for the last block and render type on this thread. */
    private static final class Built {
        ModelData data;
        RenderType renderType;
        BlockState state;
        QuadEmitter emitter;
    }

    private static final ThreadLocal<Built> BUILT = ThreadLocal.withInitial(Built::new);

    LayerModel(BakedModel wrapped) {
        super(wrapped);
    }

    /** Draws the block at {@code pos} into {@code emitter}, as the Fabric model's emitBlockQuads. */
    abstract void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter);

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        ModelData inner = super.getModelData(level, pos, state, modelData);
        return inner.derive().with(CONTEXT, new Context(level, pos.immutable())).build();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
        Context context = state == null ? null : data.get(CONTEXT);
        if (context == null) return super.getQuads(state, side, rand, data, renderType);

        Built built = BUILT.get();
        if (built.data != data || built.renderType != renderType || built.state != state) {
            QuadEmitter emitter = new QuadEmitter(originalModel, RandomSource.create(), state.getSeed(context.pos()), data, renderType);
            emitBlockQuads(context.level(), state, context.pos(), emitter);
            built.data = data;
            built.renderType = renderType;
            built.state = state;
            built.emitter = emitter;
        }
        return built.emitter.quads(side);
    }
}
