package io.arona74.aronalayersgen.client;


import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
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
 * Shifts a grounded plant's quads down so it sits on the surface of the layer block below it.
 * The offset maths is version-independent and lives in PlantGrounding.
 */
public class GroundedPlantModel implements BakedModel {

    private final BakedModel wrapped;

    public GroundedPlantModel(BakedModel wrapped) {
        this.wrapped = wrapped;
    }


    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos,
                               Supplier<RandomSource> randomSupplier, RenderContext context) {
        float yOffset = GroundedPlantRender.offset(state, blockView, pos);
        if (yOffset != 0f) {
            context.pushTransform(quad -> {
                for (int v = 0; v < 4; v++) {
                    quad.pos(v, quad.x(v), quad.y(v) + yOffset, quad.z(v));
                }
                return true;
            });
        }
        wrapped.emitBlockQuads(blockView, state, pos, randomSupplier, context);
        if (yOffset != 0f) context.popTransform();
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
