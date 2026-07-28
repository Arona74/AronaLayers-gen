package io.arona74.aronalayersgen.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * A plain layered block (1–8 layers, LAYERS property, height-based collision) that reuses
 * vanilla snow-layer mechanics but with an arbitrary texture. Used for the mod's own native
 * layer blocks (e.g. deepslate_layer, moss_layer) so surfaces whose backend has no matching
 * layer block still get one.
 *
 * <p>At layers=8 the block converts to a full block ({@link #fullBlock}), mirroring
 * {@code powder_snow_layer -> powder_snow}. Worldgen places the full block directly (see
 * LayerPlacementHelper) so this cascading {@code onBlockAdded} conversion never fires during
 * chunk init; it only handles runtime placement (e.g. a player stacking layers to 8).
 */
public class LayerBlock extends SnowLayerBlock {

    private final Block fullBlock;

    public LayerBlock(Properties settings, Block fullBlock) {
        super(settings);
        this.fullBlock = fullBlock;
    }

    /** The full block this layer becomes at layers=8. */
    public Block getFullBlock() {
        return fullBlock;
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, world, pos, oldState, notify);
        if (!world.isClientSide() && state.getValue(BlockStateProperties.LAYERS) == 8) {
            world.setBlock(pos, fullBlock.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
