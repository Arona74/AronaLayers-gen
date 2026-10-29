package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;

/**
 * Wraps the block-state models of grounded plants (see PlantGrounding) in GroundedPlantModel.
 *
 * <p>The choice is made on the block, by the same detection the shape mixin uses, so what is drawn
 * lowered and what is selected lowered never disagree. Per-state exclusions (hanging states) and
 * the "is there a layer below" test happen at draw time in PlantGrounding.offset.
 *
 * <p>Chorus plants get ChorusStemModel instead, which reaches the stem of one standing on a layer
 * down to the ground, and pillar blocks (logs among them) and mushroom stems TrunkStemModel, which
 * draws an upright log or a stem standing on a layer down to the layer.
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
                if (LayerConfig.treesOnLayers() && (block instanceof RotatedPillarBlock || block == Blocks.MUSHROOM_STEM)) {
                    ForgeRenderTypeBridge.carryOver(original, block.defaultBlockState());
                    return new TrunkStemModel(original);
                }
                if (LayerConfig.treesOnLayers() && block == Blocks.CHORUS_PLANT) {
                    ForgeRenderTypeBridge.carryOver(original, block.defaultBlockState());
                    return new ChorusStemModel(original);
                }
                if (block != Blocks.AIR && PlantGrounding.isGroundedBlock(block)) {
                    ForgeRenderTypeBridge.carryOver(original, block.defaultBlockState());
                    return new GroundedPlantModel(original);
                }
            }
            return original;
        });
    }
}
