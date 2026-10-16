package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

/**
 * Wraps the block-state models of grounded plants (see PlantGrounding) in GroundedPlantModel.
 *
 * <p>The choice is made on the block, by the same detection the shape mixin uses, so what is drawn
 * lowered and what is selected lowered never disagree. Per-state exclusions (hanging states) and
 * the "is there a layer below" test happen at draw time in PlantGrounding.offset.
 */
public class GroundedPlantModelPlugin implements ModelLoadingPlugin {

    @Override
    public void initialize(Context ctx) {
        ctx.modifyBlockModelAfterBake().register((model, context) -> {
            if (model == null) return null;
            return PlantGrounding.isGroundedBlock(context.state().getBlock())
                ? new GroundedPlantModel(model)
                : model;
        });
    }
}
