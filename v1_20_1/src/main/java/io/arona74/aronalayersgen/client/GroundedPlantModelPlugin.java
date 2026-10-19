package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Wraps the block-state models of grounded plants (see PlantGrounding) in GroundedPlantModel.
 *
 * <p>The choice is made on the block, by the same detection the shape mixin uses, so what is drawn
 * lowered and what is selected lowered never disagree. Per-state exclusions (hanging states) and
 * the "is there a layer below" test happen at draw time in PlantGrounding.offset.
 *
 * <p>This version's model-loading API hands over the model id rather than the block state. A
 * block-state model id is the block id plus a variant string, so the block is looked up from it;
 * the {@code inventory} variant is the item model and is left alone.
 */
public class GroundedPlantModelPlugin implements ModelLoadingPlugin {

    @Override
    public void onInitializeModelLoader(Context ctx) {
        ctx.modifyModelAfterBake().register((original, context) -> {
            if (original == null) return null;
            if (context.id() instanceof ModelResourceLocation id && !"inventory".equals(id.getVariant())) {
                Block block = Compat.blockFromId(id.getNamespace() + ":" + id.getPath());
                if (block != Blocks.AIR && PlantGrounding.isGroundedBlock(block)) {
                    ForgeRenderTypeBridge.carryOver(original, block.defaultBlockState());
                    return new GroundedPlantModel(original);
                }
            }
            return original;
        });
    }
}
