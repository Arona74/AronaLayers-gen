package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes layer blocks transparent to plant placement checks, so that when
 * plant_injection is enabled canSurvive() looks through a layer block to the
 * real surface below. Only meaningful when Conquest Reforged is the backend.
 *
 * <p>Per-version target: this version renamed the plant base to VegetationBlock and reused the name BushBlock for a narrower subclass, so targeting BushBlock here would silently hook only a fraction of plants.
 */
@Mixin(VegetationBlock.class)
public abstract class PlantBlockMixin {

    @Shadow
    protected abstract boolean mayPlaceOn(BlockState floor, BlockGetter world, BlockPos pos);

    @Inject(
        method = "canSurvive",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onCanPlaceAt(BlockState state, LevelReader world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = PlantPlacementHook.lookThroughLayers(world, pos, this::mayPlaceOn);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
