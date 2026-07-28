package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes layer blocks transparent to plant placement checks.
 * When plant_injection is enabled, BushBlock.canSurvive() looks through
 * layer blocks to the actual surface below.
 * Only meaningful when Conquest Reforged is the active backend.
 */
@Mixin(BushBlock.class)
public abstract class PlantBlockMixin {

    @Shadow
    protected abstract boolean mayPlaceOn(BlockState floor, BlockGetter world, BlockPos pos);

    @Inject(
        method = "canSurvive",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onCanPlaceAt(BlockState state, LevelReader world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.PLANT_INJECTION) {
            return;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = world.getBlockState(belowPos);

        if (RTFLayerInjector.hasLayerProperty(belowState)) {
            BlockPos surfacePos = belowPos.below();
            BlockState surfaceState = world.getBlockState(surfacePos);
            cir.setReturnValue(mayPlaceOn(surfaceState, world, surfacePos));
        }
    }
}
