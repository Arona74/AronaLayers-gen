package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets bamboo survive on a layer block: the same soil-level look-through as cactus and sugar cane
 * (see PlantPlacementHook.lookThroughLayerForColumnPlant). Separate from ColumnPlantSurviveMixin
 * because bamboo reads no fluid, and that mixin's fluid hook would find nothing to wrap here.
 *
 * <p>Without it worldgen bamboo stood on layers (features do not consult canSurvive) and broke at
 * the first neighbour update or growth step.
 */
@Mixin({BambooStalkBlock.class, BambooSaplingBlock.class})
public abstract class BambooSurviveMixin {

    @WrapOperation(
        method = "canSurvive",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState aronalayersgen$soilBlock(LevelReader level, BlockPos query, Operation<BlockState> original,
                                                @Local(argsOnly = true) BlockPos pos) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForColumnPlant(level, pos, query));
    }
}
