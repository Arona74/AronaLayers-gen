package io.arona74.aronalayersgen.client;


import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;

import java.util.List;
import java.util.function.Supplier;

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
public class ChorusStemModel implements BakedModel {

    private final BakedModel wrapped;

    public ChorusStemModel(BakedModel wrapped) {
        this.wrapped = wrapped;
    }


    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    /** The stem's sides, inset from the block faces like vanilla's arms (4 to 12 of 16). */
    private static final float STEM_INSET = 4f / 16f;
    /** Top of vanilla's down arm, which the extension meets when the connection is gone. */
    private static final float ARM_TOP = 4f / 16f;

    @Override
    public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos,
                               Supplier<RandomSource> randomSupplier, RenderContext context) {
        wrapped.emitBlockQuads(blockView, state, pos, randomSupplier, context);
        if (!PlantGrounding.isLayerSoil(blockView.getBlockState(pos.below()))) return;

        TextureAtlasSprite sprite = wrapped.getParticleIcon();
        QuadEmitter emitter = context.getEmitter();
        boolean armDrawn = state.hasProperty(BlockStateProperties.DOWN) && state.getValue(BlockStateProperties.DOWN);
        for (Direction face : Direction.Plane.HORIZONTAL) {
            stemSide(emitter, sprite, face, -1f, 0f);
            if (!armDrawn) stemSide(emitter, sprite, face, 0f, ARM_TOP);
        }
    }

    /**
     * One side of the stem from {@code bottom} to {@code top} (block-relative Y, at most one block
     * apart), with the texture's lower part so segments stack the way vanilla's arms do.
     */
    private static void stemSide(QuadEmitter emitter, TextureAtlasSprite sprite, Direction face, float bottom, float top) {
        emitter.square(face, STEM_INSET, bottom, 1f - STEM_INSET, top, STEM_INSET);
        float vTop = 1f - (top - bottom);
        // square() orders the corners left-top, left-bottom, right-bottom, right-top.
        emitter.uv(0, STEM_INSET, vTop);
        emitter.uv(1, STEM_INSET, 1f);
        emitter.uv(2, 1f - STEM_INSET, 1f);
        emitter.uv(3, 1f - STEM_INSET, vTop);
        emitter.spriteBake(sprite, MutableQuadView.BAKE_NORMALIZED);
        emitter.color(-1, -1, -1, -1);
        emitter.emit();
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<RandomSource> randomSupplier, RenderContext context) {
        wrapped.emitItemQuads(stack, randomSupplier, context);
    }

    // Delegate all vanilla BakedModel methods to the wrapped model

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, RandomSource random) {
        return wrapped.getQuads(state, face, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return wrapped.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return wrapped.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return wrapped.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return wrapped.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return wrapped.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return wrapped.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return wrapped.getOverrides();
    }
}
