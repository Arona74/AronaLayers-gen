package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts block state replacement to catch snow layer removal.
 * When break_snow_layers_to_mapped_layers is enabled and a snow layer is broken,
 * the snow is replaced by the mapped layer block based on the surface below,
 * with the layer value reduced by 1.
 * Works with both CR and VP backends.
 */
@Mixin(AbstractBlock.class)
public class SnowBlockMixin {

    @Inject(
        method = "onStateReplaced",
        at = @At("TAIL")
    )
    private void onSnowRemoved(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved, CallbackInfo ci) {
        if (!LayerConfig.BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS) {
            return;
        }

        if (state.getBlock() != Blocks.SNOW) {
            return;
        }

        if (world.isClient()) {
            return;
        }

        if (!newState.isAir()) {
            return;
        }

        int snowLayers = state.get(Properties.LAYERS);
        int newLayerCount = snowLayers - 1;

        if (newLayerCount < 1) {
            return;
        }

        BlockPos belowPos = pos.down();
        BlockState belowState = world.getBlockState(belowPos);
        Block belowBlock = belowState.getBlock();

        if (!RTFLayerInjector.hasMappingFor(belowBlock)) {
            return;
        }

        Block layerBlock = RTFLayerInjector.getMappedLayerBlock(belowBlock, newLayerCount);
        if (layerBlock == null || layerBlock == Blocks.SNOW) {
            return;
        }

        BlockState layerState = layerBlock.getDefaultState();
        layerState = RTFLayerInjector.applyLayerCount(layerState, layerBlock, newLayerCount);

        world.setBlockState(pos, layerState, Block.NOTIFY_ALL);

        if (LayerConfig.logSnow()) {
            AronaLayersGen.LOGGER.info("[SnowBreak] Replaced snow (layers={}) with {} (layers={}) at {}",
                snowLayers,
                net.minecraft.registry.Registries.BLOCK.getId(layerBlock),
                newLayerCount, pos);
        }
    }
}
