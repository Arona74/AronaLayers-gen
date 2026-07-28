package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.injection.SnowLayerBreakHook;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches snow layer removal; the replacement logic lives in SnowLayerBreakHook.
 *
 * <p>This version has no onRemove: it was replaced by affectNeighborsAfterRemoval,
 * which runs after the block is gone and carries no newState parameter, so the
 * current occupant is read back from the level instead.
 */
@Mixin(BlockBehaviour.class)
public class SnowBlockMixin {

    @Inject(
        method = "affectNeighborsAfterRemoval",
        at = @At("TAIL")
    )
    private void onSnowRemoved(BlockState state, ServerLevel world, BlockPos pos,
                               boolean movedByPiston, CallbackInfo ci) {
        SnowLayerBreakHook.onSnowRemoved(state, world, pos, world.getBlockState(pos));
    }
}
