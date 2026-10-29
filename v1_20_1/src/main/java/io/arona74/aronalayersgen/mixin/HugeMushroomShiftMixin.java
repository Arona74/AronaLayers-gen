package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Huge mushrooms get the trees' treatment (see TreeShiftMixin): a generated one starts one block
 * higher, so the layer pass can fill the cell its stem would have stood in.
 *
 * <p>Unlike a tree, the feature checks its own soil, the block directly under the stem, so that read
 * looks through the empty cell, or through a layer when bone meal grows a mushroom standing on one
 * (see LayerPlacementHelper.mushroomSoil). Only the first block read in isValidPosition is the soil;
 * the rest check the space for the cap. Per version: the feature classes move between packages in
 * 26.x.
 */
@Mixin(AbstractHugeMushroomFeature.class)
public abstract class HugeMushroomShiftMixin {

    @ModifyExpressionValue(
        method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;origin()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos aronalayersgen$shiftOrigin(BlockPos origin,
                                                @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) FeaturePlaceContext<HugeMushroomFeatureConfiguration> context) {
        return LayerPlacementHelper.shiftTreeOrigin(context.level(), origin, false);
    }

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("RETURN"))
    private void aronalayersgen$endPlacement(FeaturePlaceContext<HugeMushroomFeatureConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        LayerPlacementHelper.endTreePlacement();
    }

    @WrapOperation(
        method = "isValidPosition",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 0)
    )
    private BlockState aronalayersgen$soilThroughLayer(LevelAccessor level, BlockPos query, Operation<BlockState> original) {
        return original.call(level, LayerPlacementHelper.mushroomSoil(level, query));
    }
}
