package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Placing bamboo by hand: getStateForPlacement tests the block below against the bamboo soil tag
 * itself and returns null (placement refused) before canSurvive is ever asked. Only its first
 * block read is the one below the clicked position; the second reads the block above.
 */
@Mixin(BambooStalkBlock.class)
public abstract class BambooPlacementMixin {

    @WrapOperation(
        method = "getStateForPlacement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 0)
    )
    private BlockState aronalayersgen$soilBlock(Level level, BlockPos below, Operation<BlockState> original) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForColumnPlant(level, below.above(), below));
    }
}
