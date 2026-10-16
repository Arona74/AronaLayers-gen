package io.arona74.aronalayersgen.client;


import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

/**
 * Shifts a plant's quads down so it sits on the surface of a partial-height
 * layer block below it.
 *
 * The 26.x line moves everything this touches again, from 26.1 onwards:
 * BlockStateModel to net.minecraft.client.renderer.block.dispatch,
 * BlockModelPart to BlockStateModelPart, BlockAndTintGetter into a client
 * package, and the whole Fabric renderer API from
 * net.fabricmc.fabric.api.renderer.v1 to
 * net.fabricmc.fabric.api.client.renderer.v1. particleIcon also became
 * particleMaterial, returning a baked Material rather than a sprite, and
 * materialFlags is new.
 *
 * The offset maths itself is version-independent and lives in PlantGrounding.
 */
public class GroundedPlantModel implements BlockStateModel {

    private final BlockStateModel wrapped;

    public GroundedPlantModel(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos,
                          BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        float yOffset = GroundedPlantRender.offset(state, blockView, pos);
        if (yOffset != 0f) {
            emitter.pushTransform(quad -> {
                for (int v = 0; v < 4; v++) {
                    quad.pos(v, quad.x(v), quad.y(v) + yOffset, quad.z(v));
                }
                return true;
            });
        }
        wrapped.emitQuads(emitter, blockView, pos, state, random, cullTest);
        if (yOffset != 0f) emitter.popTransform();
    }

    /**
     * Geometry key that accounts for the offset.
     *
     * Forwarding the wrapped model's key unchanged would be wrong, because this
     * offset depends on the block *below*: a plant on a layer block would reuse
     * the geometry of the same plant on flat ground. Returning null -- never
     * cacheable -- is correct but expensive now that every grounded plant is
     * wrapped. Composing the two is both: same wrapped geometry and same offset
     * means the same result. A null from the wrapped model still means not
     * cacheable and has to propagate.
     */
    @Override
    public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos,
                                    BlockState state, RandomSource random) {
        Object wrappedKey = wrapped.createGeometryKey(blockView, pos, state, random);
        if (wrappedKey == null) return null;
        float yOffset = GroundedPlantRender.offset(state, blockView, pos);
        return yOffset == 0f ? wrappedKey : List.of(wrappedKey, yOffset);
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
