package io.arona74.aronalayersgen.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Version-specific entityInside signature; the effects themselves are shared. */
public class PowderSnowLayerBlock extends PowderSnowLayerBlockBase {

    public PowderSnowLayerBlock(Properties settings) {
        super(settings);
    }

    @Override
    protected void entityInside(BlockState state, Level world, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier applier, boolean flag) {
        applyInsideEffects(state, entity);
    }
}
