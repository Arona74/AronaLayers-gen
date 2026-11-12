package io.arona74.aronalayersgen.client;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Shifts a grounded plant's quads down so it sits on the surface of the layer block below it.
 * The offset maths is version-independent and lives in PlantGrounding.
 */
public class GroundedPlantModel extends LayerModel {

    public GroundedPlantModel(BlockStateModel wrapped) {
        super(wrapped);
    }

    @Override
    void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter) {
        float yOffset = GroundedPlantRender.offset(state, level, pos);
        if (yOffset == 0f) {
            emitter.emitWrapped(wrapped, state, null);
            return;
        }
        emitter.emitWrapped(wrapped, state, quad -> {
            for (int v = 0; v < 4; v++) {
                quad.pos(v, quad.x(v), quad.y(v) + yOffset, quad.z(v));
            }
            return true;
        });
    }

    /** Same wrapped geometry and same offset means the same result (see the Fabric model). */
    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        Object wrappedKey = wrapped.createGeometryKey(level, pos, state, random);
        if (wrappedKey == null) return null;
        float yOffset = GroundedPlantRender.offset(state, level, pos);
        return yOffset == 0f ? wrappedKey : List.of(wrappedKey, yOffset);
    }
}
