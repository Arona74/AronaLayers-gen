package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
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
 * <p>Unlike a tree, the feature checks its own soil, the block directly under the stem (26.x tests
 * it with canPlaceOn against {@code pos.below()}), so that position looks through the empty cell,
 * or through a layer when bone meal grows a mushroom standing on one (see
 * LayerPlacementHelper.mushroomSoil). The first below() in isValidPosition is the soil.
 */
@Mixin(AbstractHugeMushroomFeature.class)
public abstract class HugeMushroomShiftMixin {

    @ModifyExpressionValue(
        method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;origin()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos aronalayersgen$shiftOrigin(BlockPos origin,
                                                @Local(argsOnly = true) FeaturePlaceContext<HugeMushroomFeatureConfiguration> context) {
        return LayerPlacementHelper.shiftTreeOrigin(context.level(), origin, false);
    }

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("RETURN"))
    private void aronalayersgen$endPlacement(FeaturePlaceContext<HugeMushroomFeatureConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        LayerPlacementHelper.endTreePlacement();
    }

    @ModifyExpressionValue(
        method = "isValidPosition",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;", ordinal = 0)
    )
    private BlockPos aronalayersgen$soilThroughLayer(BlockPos soil, @Local(argsOnly = true) WorldGenLevel level) {
        return LayerPlacementHelper.mushroomSoil(level, soil);
    }
}
