package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
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
 * down to the ground, and pillar blocks (logs among them), mushroom stems and posts (fences, walls,
 * panes, bars) TrunkStemModel, which draws one standing on a layer down to the layer.
 */
public class GroundedPlantModelPlugin implements ModelLoadingPlugin {

    @Override
    public void initialize(Context ctx) {
        ctx.modifyBlockModelAfterBake().register((model, context) -> {
            if (model == null) return null;
            Block block = context.state().getBlock();
            boolean trunk = LayerConfig.treesOnLayers() && (block instanceof RotatedPillarBlock || block == Blocks.MUSHROOM_STEM);
            if (trunk || LayerConfig.postsOnLayers() && PlantGrounding.isPostBlock(block)) return new TrunkStemModel(model, context.state());
            if (LayerConfig.treesOnLayers() && block == Blocks.CHORUS_PLANT) return new ChorusStemModel(model);
            return PlantGrounding.isGroundedBlock(block)
                ? new GroundedPlantModel(model)
                : model;
        });
    }
}
