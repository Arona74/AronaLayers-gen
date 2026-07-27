package io.arona74.aronalayersgen.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SnowBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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
public class LayerBlock extends SnowBlock {

    private final Block fullBlock;

    public LayerBlock(Settings settings, Block fullBlock) {
        super(settings);
        this.fullBlock = fullBlock;
    }

    /** The full block this layer becomes at layers=8. */
    public Block getFullBlock() {
        return fullBlock;
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient() && state.get(Properties.LAYERS) == 8) {
            world.setBlockState(pos, fullBlock.getDefaultState(), Block.NOTIFY_ALL);
        }
    }
}
