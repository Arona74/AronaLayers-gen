package io.arona74.crlayers.mixin;

import io.arona74.crlayers.LayerConfig;
import io.arona74.crlayers.injection.RTFLayerInjector;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TestableWorld;
import net.minecraft.world.gen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes layer blocks replaceable by tree features.
 * When tree_injection is enabled, TreeFeature.canReplace() treats layer blocks
 * as replaceable, allowing tree trunks and foliage to overwrite them during
 * feature generation. This is the single chokepoint used by TrunkPlacer,
 * FoliagePlacer, and RootPlacer for all tree types (including 2x2 large trees).
 */
@Mixin(TreeFeature.class)
public class TreeFeatureMixin {

    @Inject(
        method = "canReplace",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onCanReplace(TestableWorld world, BlockPos pos,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.TREE_INJECTION) {
            return;
        }

        // TestableWorld only exposes testBlockState(pos, predicate), not getBlockState().
        // Use the predicate to check for layer blocks and short-circuit if found.
        // We need to use a mutable holder since the predicate can't directly cancel.
        final boolean[] isLayerBlock = {false};
        world.testBlockState(pos, state -> {
            if (RTFLayerInjector.hasLayerProperty(state)) {
                isLayerBlock[0] = true;
            }
            return false; // Don't affect the testBlockState result
        });

        if (isLayerBlock[0]) {
            cir.setReturnValue(true);
            // No need to call cancel() - setReturnValue implies cancellation for returnable callbacks
        }
    }
}
