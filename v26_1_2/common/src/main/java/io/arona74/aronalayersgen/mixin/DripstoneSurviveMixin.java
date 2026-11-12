package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets an upward-pointing pointed dripstone (a stalagmite) stand on a layer block, which plant
 * grounding then lowers it onto, like a cactus. Same soil-level look-through as the column plants
 * (see PlantPlacementHook.lookThroughLayerForColumnPlant).
 *
 * <p>Both placing and surviving go through isValidPointedDripstonePlacement, which reads the block
 * behind the tip. For a stalagmite that is the block below, which the look-through moves under the
 * layer; for a stalactite it is the block above, which it leaves alone.
 *
 * <p>Per-version: 26.2 moved this into SpeleothemBlock.isValidSpeleothemPlacement.
 */
@Mixin(PointedDripstoneBlock.class)
public abstract class DripstoneSurviveMixin {

    @WrapOperation(
        method = "isValidPointedDripstonePlacement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private static BlockState aronalayersgen$soilBlock(LevelReader level, BlockPos query, Operation<BlockState> original,
                                                       @Local(argsOnly = true) BlockPos pos) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForColumnPlant(level, pos, query));
    }
}
