package io.arona74.aronalayersgen.client;


import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Draws an upright log (or a huge mushroom's stem) standing on a layer block down to the layer's
 * top, so a tree that stands on a layer (generated one block higher, or grown from a sapling on the
 * layer) reads as rooted in it rather than floating over it.
 *
 * <p>The extension is the log's own side faces, moved into the layer's cell and cut off at the
 * layer's top with their texture cut to match, so the bark continues unbroken from the block above.
 * The layer itself is hidden inside it. Whether a log qualifies is PlantGrounding.isStandingTrunk,
 * which the outline and collision use too.
 */
public class TrunkStemModel implements BlockStateModel {

    private final BlockStateModel wrapped;

    public TrunkStemModel(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos,
                          BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        wrapped.emitQuads(emitter, blockView, pos, state, random, cullTest);
        if (!PlantGrounding.isStandingTrunk(state, blockView, pos)) return;
        float top = PlantGrounding.layerTop(blockView, pos.below());
        if (top >= 1f) return;

        List<BlockStateModelPart> parts = new ArrayList<>();
        wrapped.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
            for (Direction face : Direction.Plane.HORIZONTAL) {
                for (BakedQuad quad : part.getQuads(face)) {
                    emitter.fromBakedQuad(quad);
                    if (cutDown(emitter, top)) emitter.emit();
                }
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

    /** The wrapped geometry, plus the layer height the extension is cut at: it depends on the block below. */
    @Override
    public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos,
                                    BlockState state, RandomSource random) {
        Object wrappedKey = wrapped.createGeometryKey(blockView, pos, state, random);
        if (wrappedKey == null) return null;
        if (!PlantGrounding.isStandingTrunk(state, blockView, pos)) return wrappedKey;
        return List.of(wrappedKey, PlantGrounding.layerTop(blockView, pos.below()));
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
