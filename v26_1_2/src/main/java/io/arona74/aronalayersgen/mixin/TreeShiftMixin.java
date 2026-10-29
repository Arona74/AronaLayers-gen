package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Starts a generated tree one block higher, so the layer pass can put a layer in the cell its trunk
 * would have stood in (see LayerPlacementHelper.shiftTreeOrigin). TreeFeature.place reads its
 * origin once and hands it to everything after: trunk, foliage, decorators. Per version: the
 * feature classes move between packages in 26.x.
 */
@Mixin(TreeFeature.class)
public class TreeShiftMixin {

    @ModifyExpressionValue(
        method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;origin()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos aronalayersgen$shiftOrigin(BlockPos origin,
                                                @Local(argsOnly = true) FeaturePlaceContext<TreeConfiguration> context) {
        return LayerPlacementHelper.shiftTreeOrigin(context.level(), origin, context.config().rootPlacer.isPresent());
    }

    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("RETURN"))
    private void aronalayersgen$endPlacement(FeaturePlaceContext<TreeConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        LayerPlacementHelper.endTreePlacement();
    }
}
