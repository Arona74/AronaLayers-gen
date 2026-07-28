package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.injection.SnowLayerBreakHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Catches snow layer removal; the replacement logic lives in SnowLayerBreakHook. */
@Mixin(BlockBehaviour.class)
public class SnowBlockMixin {

    @Inject(
        method = "onRemove",
        at = @At("TAIL")
    )
    private void onSnowRemoved(BlockState state, Level world, BlockPos pos, BlockState newState,
                               boolean moved, CallbackInfo ci) {
        SnowLayerBreakHook.onSnowRemoved(state, world, pos, newState);
    }
}
