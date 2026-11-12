package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.2+ version: pointed dripstone's placement rule moved into the new SpeleothemBlock base class,
 * as the instance method isValidSpeleothemPlacement. Same look-through as the shared copy.
 */
@Mixin(SpeleothemBlock.class)
public abstract class DripstoneSurviveMixin {

    @WrapOperation(
        method = "isValidSpeleothemPlacement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState aronalayersgen$soilBlock(LevelReader level, BlockPos query, Operation<BlockState> original,
                                                @Local(argsOnly = true) BlockPos pos) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForColumnPlant(level, pos, query));
    }
}
