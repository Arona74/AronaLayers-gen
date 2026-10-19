package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets sea pickles, kelp (and the other growing plants), and small and big dripleaf survive on a
 * layer block, with the same soil-level look-through as cactus, sugar cane and bamboo (see
 * PlantPlacementHook.lookThroughLayerForColumnPlant): reads one block below the plant are moved
 * below the layer.
 *
 * <p>These override canSurvive with their own soil test, so PlantBlockMixin's hook on the plant base
 * class never runs for them. GrowingPlantBlock holds the test for kelp and kelp_plant (and twisting
 * vines); the vines that grow downward read the block above, which the look-through leaves alone.
 */
@Mixin({SeaPickleBlock.class, GrowingPlantBlock.class, SmallDripleafBlock.class,
        BigDripleafBlock.class, BigDripleafStemBlock.class})
public abstract class PlantSoilSurviveMixin {

    @WrapOperation(
        method = "canSurvive",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private BlockState aronalayersgen$soilBlock(LevelReader level, BlockPos query, Operation<BlockState> original,
                                                @Local(argsOnly = true) BlockPos pos) {
        return original.call(level, PlantPlacementHook.lookThroughLayerForColumnPlant(level, pos, query));
    }
}
