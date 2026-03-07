package io.arona74.crlayers.mixin;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import io.arona74.crlayers.injection.RTFLayerInjector;
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
 * the snow is replaced by the block_mappings layer block based on the surface below,
 * with the layer value reduced by 1.
 *
 * Targets AbstractBlock.class where onStateReplaced is defined.
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

        // Only handle snow layer blocks
        if (state.getBlock() != Blocks.SNOW) {
            return;
        }

        // Only act on server side
        if (world.isClient()) {
            return;
        }

        // Only act when snow was replaced with air (broken, not replaced by another block)
        if (!newState.isAir()) {
            return;
        }

        // Get the snow layer count before it was broken
        int snowLayers = state.get(Properties.LAYERS);
        int newLayerCount = snowLayers - 1;

        // If reduced layer count is 0, leave as air (no layer to place)
        if (newLayerCount < 1) {
            return;
        }

        // Get the block below to determine the mapping
        BlockPos belowPos = pos.down();
        BlockState belowState = world.getBlockState(belowPos);
        Block belowBlock = belowState.getBlock();

        // Check if the block below has a layer mapping
        if (!RTFLayerInjector.hasMappingFor(belowBlock)) {
            return;
        }

        // Get the mapped layer block
        Block layerBlock = RTFLayerInjector.getMappedLayerBlock(belowBlock, newLayerCount);
        if (layerBlock == null || layerBlock == Blocks.SNOW) {
            // No valid mapping or fallback to snow - skip replacement
            return;
        }

        // Create the layer block state with the reduced layer count
        BlockState layerState = layerBlock.getDefaultState();
        layerState = RTFLayerInjector.applyLayerCount(layerState, layerBlock, newLayerCount);

        // Place the mapped layer block
        world.setBlockState(pos, layerState, Block.NOTIFY_ALL);

        if (LayerConfig.DEBUG_LOGGING) {
            CRLayers.LOGGER.info("[SnowBreak] Replaced snow (layers={}) with {} (layers={}) at {}",
                snowLayers,
                net.minecraft.registry.Registries.BLOCK.getId(layerBlock),
                newLayerCount, pos);
        }
    }
}
