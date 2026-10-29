package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a chorus plant stand on a layer block, which worldgen lifts it onto (see
 * LayerPlacementHelper.liftChorusPlant). Both blocks ask for end stone or chorus directly below
 * them, and below a neighbouring chorus plant; each of those reads moves under a layer block
 * instead (see PlantPlacementHook.lookThroughLayerForChorus).
 */
@Mixin({ChorusPlantBlock.class, ChorusFlowerBlock.class})
public abstract class ChorusSurviveMixin {

    @WrapOperation(
        method = "canSurvive",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState aronalayersgen$supportBlock(LevelReader level, BlockPos query, Operation<BlockState> original,
                                                   @Local(argsOnly = true) BlockPos pos) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForChorus(level, pos, query));
    }
}
