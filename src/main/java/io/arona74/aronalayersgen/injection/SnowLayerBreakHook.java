package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Body of SnowBlockMixin, kept out of the mixin itself.
 *
 * <p>When break_snow_layers_to_mapped_layers is enabled and a snow layer is
 * broken, the snow is replaced by the mapped layer block for the surface below,
 * with the layer count reduced by one. Works with both the CR and VP backends.
 *
 * <p>The mixin is per-version: up to 1.21.1 the hook is
 * BlockBehaviour.onRemove(state, level, pos, newState, moved), but 1.21.11
 * replaced it with affectNeighborsAfterRemoval(state, serverLevel, pos, moved),
 * which drops the newState parameter — so each version resolves "what is at this
 * position now" its own way and calls in here.
 */
public final class SnowLayerBreakHook {
    private SnowLayerBreakHook() {}

    /**
     * @param state    the snow state that was removed
     * @param newState what now occupies the position; the replacement only runs
     *                 when this is air, i.e. the snow was actually broken
     */
    public static void onSnowRemoved(BlockState state, Level world, BlockPos pos, BlockState newState) {
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
                Compat.blockId(layerBlock),
                newLayerCount, pos);
        }
    }
}
