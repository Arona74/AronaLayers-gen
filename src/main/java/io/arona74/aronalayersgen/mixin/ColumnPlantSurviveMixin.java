package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets cactus and sugar cane stand on a layer block, which plant grounding then lowers them onto.
 * They do not extend the plant base class, so PlantBlockMixin's look-through never reaches them;
 * this one moves vanilla's soil-level reads below the layer instead (see
 * PlantPlacementHook.lookThroughLayerForColumnPlant).
 */
@Mixin({CactusBlock.class, SugarCaneBlock.class})
public abstract class ColumnPlantSurviveMixin {

    @WrapOperation(
        method = "canSurvive",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState aronalayersgen$soilBlock(LevelReader level, BlockPos query, Operation<BlockState> original,
                                                @Local(argsOnly = true) BlockPos pos) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForColumnPlant(level, pos, query));
    }

    @WrapOperation(
        method = "canSurvive",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;")
    )
    private FluidState aronalayersgen$soilFluid(LevelReader level, BlockPos query, Operation<FluidState> original,
                                                @Local(argsOnly = true) BlockPos pos) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForColumnPlant(level, pos, query));
    }
}
