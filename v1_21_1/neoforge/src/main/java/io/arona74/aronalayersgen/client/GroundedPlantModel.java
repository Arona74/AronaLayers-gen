package io.arona74.aronalayersgen.client;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shifts a grounded plant's quads down so it sits on the surface of the layer block below it.
 * The offset maths is version-independent and lives in PlantGrounding.
 */
public class GroundedPlantModel extends LayerModel {

    public GroundedPlantModel(BakedModel wrapped) {
        super(wrapped);
    }

    @Override
    void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, QuadEmitter emitter) {
        float yOffset = GroundedPlantRender.offset(state, level, pos);
        if (yOffset == 0f) {
            emitter.emitWrapped(state, null);
            return;
        }
        emitter.emitWrapped(state, quad -> {
            for (int v = 0; v < 4; v++) {
                quad.pos(v, quad.x(v), quad.y(v) + yOffset, quad.z(v));
            }
            return true;
        });
    }
}
