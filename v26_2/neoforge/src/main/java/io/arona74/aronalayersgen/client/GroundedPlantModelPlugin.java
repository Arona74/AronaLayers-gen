package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * NeoForge, Minecraft 26.2: wraps the block-state models of grounded plants (see PlantGrounding)
 * in GroundedPlantModel, as the Fabric model-loading plugin of the same name does.
 *
 * <p>The choice is made on the block, by the same detection the shape mixin uses, so what is drawn
 * lowered and what is selected lowered never disagree. Per-state exclusions (hanging states) and
 * the "is there a layer below" test happen at draw time in PlantGrounding.offset.
 *
 * <p>Chorus plants get ChorusStemModel instead, and pillar blocks (logs among them), mushroom stems
 * and posts (fences, walls, panes, bars) TrunkStemModel.
 */
public final class GroundedPlantModelPlugin {
    private GroundedPlantModelPlugin() {}

    public static void wrapModels(ModelEvent.ModifyBakingResult event) {
        event.getBakingResult().blockStateModels().replaceAll(GroundedPlantModelPlugin::wrap);
    }

    private static BlockStateModel wrap(BlockState state, BlockStateModel model) {
        if (model == null) return null;
        Block block = state.getBlock();
        boolean trunk = LayerConfig.treesOnLayers() && (block instanceof RotatedPillarBlock || block == Blocks.MUSHROOM_STEM);
        if (trunk || LayerConfig.postsOnLayers() && PlantGrounding.isPostBlock(block)) return new TrunkStemModel(model, state);
        if (LayerConfig.treesOnLayers() && block == Blocks.CHORUS_PLANT) return new ChorusStemModel(model);
        return PlantGrounding.isGroundedBlock(block)
            ? new GroundedPlantModel(model)
            : model;
    }
}
