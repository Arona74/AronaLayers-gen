package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.Map;

/**
 * NeoForge, Minecraft 1.21.1: wraps the block-state models of grounded plants (see PlantGrounding)
 * in GroundedPlantModel, as the Fabric model-loading plugin of the same name does.
 *
 * <p>The choice is made on the block, by the same detection the shape mixin uses, so what is drawn
 * lowered and what is selected lowered never disagree. Per-state exclusions (hanging states) and
 * the "is there a layer below" test happen at draw time in PlantGrounding.offset.
 *
 * <p>Chorus plants get ChorusStemModel instead, and pillar blocks (logs among them), mushroom stems
 * and posts (fences, walls, panes, bars) TrunkStemModel.
 *
 * <p>A block-state model id is the block id plus a variant string, so the block is looked up from
 * it; the {@code inventory} variant is the item model and is left alone. The wrappers keep the
 * render types the wrapped model asks for (BakedModelWrapper passes getRenderTypes through), so
 * models that set their own in JSON keep them.
 */
public final class GroundedPlantModelPlugin {
    private GroundedPlantModelPlugin() {}

    public static void wrapModels(ModelEvent.ModifyBakingResult event) {
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            BakedModel wrapped = wrap(entry.getKey(), entry.getValue());
            if (wrapped != entry.getValue()) entry.setValue(wrapped);
        }
    }

    private static BakedModel wrap(ModelResourceLocation id, BakedModel original) {
        if (original == null || "inventory".equals(id.variant())) return original;
        Block block = Compat.blockFromId(id.id().toString());
        boolean trunk = LayerConfig.treesOnLayers() && (block instanceof RotatedPillarBlock || block == Blocks.MUSHROOM_STEM);
        if (trunk || LayerConfig.postsOnLayers() && PlantGrounding.isPostBlock(block)) {
            return new TrunkStemModel(original);
        }
        if (LayerConfig.treesOnLayers() && block == Blocks.CHORUS_PLANT) {
            return new ChorusStemModel(original);
        }
        if (block != Blocks.AIR && PlantGrounding.isGroundedBlock(block)) {
            return new GroundedPlantModel(original);
        }
        return original;
    }
}
