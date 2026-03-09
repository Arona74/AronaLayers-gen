package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import net.minecraft.block.BlockState;
import net.minecraft.block.PlantBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes layer blocks transparent to plant placement checks.
 * When plant_injection is enabled, PlantBlock.canPlaceAt() looks through
 * layer blocks to the actual surface below.
 * Only meaningful when Conquest Reforged is the active backend.
 */
@Mixin(PlantBlock.class)
public abstract class PlantBlockMixin {

    @Shadow
    protected abstract boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos);

    @Inject(
        method = "canPlaceAt",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onCanPlaceAt(BlockState state, WorldView world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.PLANT_INJECTION) {
            return;
        }

        BlockPos belowPos = pos.down();
        BlockState belowState = world.getBlockState(belowPos);

        if (RTFLayerInjector.hasLayerProperty(belowState)) {
            BlockPos surfacePos = belowPos.down();
            BlockState surfaceState = world.getBlockState(surfacePos);
            cir.setReturnValue(canPlantOnTop(surfaceState, world, surfacePos));
        }
    }
}
