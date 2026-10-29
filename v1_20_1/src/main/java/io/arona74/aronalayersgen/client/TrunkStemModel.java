package io.arona74.aronalayersgen.client;


import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
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
 * Draws an upright log (or a huge mushroom's stem) standing on a layer block down to the layer's top, so a tree that stands on
 * a layer (generated one block higher, or grown from a sapling on the layer) reads as rooted in it
 * rather than floating over it.
 *
 * <p>The extension is the log's own side faces, moved into the layer's cell and cut off at the
 * layer's top with their texture cut to match, so the bark continues unbroken from the block above.
 * The layer itself is hidden inside it. Whether a log qualifies is PlantGrounding.isStandingTrunk,
 * which the outline and collision use too.
 */
public class TrunkStemModel implements BakedModel {

    private final BakedModel wrapped;
    private static RenderMaterial material;

    public TrunkStemModel(BakedModel wrapped) {
        this.wrapped = wrapped;
    }


    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos,
                               Supplier<RandomSource> randomSupplier, RenderContext context) {
        wrapped.emitBlockQuads(blockView, state, pos, randomSupplier, context);
        if (!PlantGrounding.isStandingTrunk(state, blockView, pos)) return;
        float top = PlantGrounding.layerTop(blockView, pos.below());
        if (top >= 1f) return;

        QuadEmitter emitter = context.getEmitter();
        for (Direction face : Direction.Plane.HORIZONTAL) {
            for (BakedQuad quad : wrapped.getQuads(state, face, randomSupplier.get())) {
                emitter.fromVanilla(quad, material(), null);
                if (cutDown(emitter, top)) emitter.emit();
            }
        }
    }

    /**
     * Moves a side quad spanning its block's height one block down and cuts its bottom edge to
     * {@code top}, interpolating the texture along each vertical edge so it is cut, not squashed.
     *
     * @return false for a quad that is not a full-height side, which is left out
     */
    private static boolean cutDown(QuadEmitter quad, float top) {
        float[] y = new float[4];
        float[] u = new float[4];
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
            y[i] = quad.y(i);
            u[i] = quad.u(i);
            v[i] = quad.v(i);
        }
        for (int i = 0; i < 4; i++) {
            if (y[i] > 0.5f) {
                quad.pos(i, quad.x(i), y[i] - 1f, quad.z(i));
                continue;
            }
            // The upper corner on the same vertical edge.
            int j = -1;
            for (int k = 0; k < 4; k++) {
                if (k != i && y[k] > 0.5f && quad.x(k) == quad.x(i) && quad.z(k) == quad.z(i)) j = k;
            }
            if (j < 0) return false;
            float t = (y[j] - top) / (y[j] - y[i]);
            quad.pos(i, quad.x(i), top - 1f, quad.z(i));
            quad.uv(i, u[j] + (u[i] - u[j]) * t, v[j] + (v[i] - v[j]) * t);
        }
        quad.cullFace(null);
        return true;
    }

    private static RenderMaterial material() {
        if (material == null) {
            material = RendererAccess.INSTANCE.getRenderer().materialFinder().find();
        }
        return material;
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
