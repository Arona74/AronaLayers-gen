package io.arona74.aronalayersgen.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.function.Supplier;

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

    private final Supplier<Block> fullBlock;

    public LayerBlock(Properties settings, Block fullBlock) {
        this(settings, () -> fullBlock);
    }

    /**
     * For a full block owned by another mod (Create's zinc ore), looked up when first needed: that
     * mod may register its blocks after this one, so it cannot be resolved at construction.
     */
    public LayerBlock(Properties settings, Supplier<Block> fullBlock) {
        super(settings);
        this.fullBlock = fullBlock;
    }

    /** The full block this layer becomes at layers=8, or AIR when its mod is not installed. */
    public Block getFullBlock() {
        return fullBlock.get();
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, world, pos, oldState, notify);
        Block full = getFullBlock();
        if (!world.isClientSide() && state.getValue(BlockStateProperties.LAYERS) == 8 && full != Blocks.AIR) {
            world.setBlock(pos, full.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
