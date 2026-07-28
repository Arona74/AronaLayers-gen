package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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
@Mixin(BlockBehaviour.class)
public class SnowBlockMixin {

    @Inject(
        method = "onRemove",
        at = @At("TAIL")
    )
    private void onSnowRemoved(BlockState state, Level world, BlockPos pos, BlockState newState, boolean moved, CallbackInfo ci) {
        if (!LayerConfig.BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS) {
            return;
        }

        if (state.getBlock() != Blocks.SNOW) {
            return;
        }

        if (world.isClientSide()) {
            return;
        }

        if (!newState.isAir()) {
            return;
        }

        int snowLayers = state.getValue(BlockStateProperties.LAYERS);
        int newLayerCount = snowLayers - 1;

        if (newLayerCount < 1) {
            return;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = world.getBlockState(belowPos);
        Block belowBlock = belowState.getBlock();

        if (!RTFLayerInjector.hasMappingFor(belowBlock)) {
            return;
        }

        Block layerBlock = RTFLayerInjector.getMappedLayerBlock(belowBlock, newLayerCount);
        if (layerBlock == null || layerBlock == Blocks.SNOW) {
            return;
        }

        BlockState layerState = layerBlock.defaultBlockState();
        layerState = RTFLayerInjector.applyLayerCount(layerState, layerBlock, newLayerCount);

        world.setBlock(pos, layerState, Block.UPDATE_ALL);

        if (LayerConfig.logSnow()) {
            AronaLayersGen.LOGGER.info("[SnowBreak] Replaced snow (layers={}) with {} (layers={}) at {}",
                snowLayers,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(layerBlock),
                newLayerCount, pos);
        }
    }
}
