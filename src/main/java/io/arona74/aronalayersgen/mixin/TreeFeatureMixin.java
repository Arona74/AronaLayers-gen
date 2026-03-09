package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
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
 * as replaceable, allowing tree trunks and foliage to overwrite them.
 * Works with both CR and VP layer blocks.
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

        final boolean[] isLayerBlock = {false};
        world.testBlockState(pos, state -> {
            if (RTFLayerInjector.hasLayerProperty(state)) {
                isLayerBlock[0] = true;
            }
            return false;
        });

        if (isLayerBlock[0]) {
            cir.setReturnValue(true);
        }
    }
}
