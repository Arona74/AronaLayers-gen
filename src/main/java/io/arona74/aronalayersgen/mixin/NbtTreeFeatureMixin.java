package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.feature.TreeFeature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Safety-net for vanilla (non-RTF) worlds: intercepts TreeFeature.generate()
 * and places a CR NBT tree before vanilla gets a chance to.
 *
 * With RTF active, TreeFeature.generate() is never called — tree replacement
 * is handled instead by NbtTreeInjector.scanAndReplace() via the server-tick queue.
 */
@Mixin(TreeFeature.class)
public class NbtTreeFeatureMixin {

    @Inject(
        method = "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onGenerate(FeatureContext<TreeFeatureConfig> context,
                             CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.CR_NBT_TREES) return;
        if (LayerConfig.logNbtTrees())
            AronaLayersGen.LOGGER.info("[NbtTrees] vanilla-mixin fired pos={}", context.getOrigin());

        if (context.getWorld() instanceof ServerWorld) {
            // Sapling growing — place immediately, world is fully loaded
            boolean placed = NbtTreeInjector.tryPlaceTree(
                context.getWorld(), context.getOrigin(), context.getRandom());
            if (placed) {
                cir.setReturnValue(true);
            } else if (!LayerConfig.CR_NBT_TREES_VANILLA_FALLBACK) {
                // Fallback disabled: suppress vanilla; generate() returns false so the sapling is restored.
                cir.setReturnValue(false);
            }
            // else: fallback enabled, don't touch cir → vanilla tree runs normally
        } else {
            // Worldgen — cancel vanilla and defer, but only if this biome is configured
            // (or vanilla_fallback=false, in which case we always cancel).
            if (NbtTreeInjector.willHandleWorldgenTree(context.getWorld(), context.getOrigin())) {
                NbtTreeInjector.queueWorldgenTree(context.getOrigin());
                cir.setReturnValue(true);
            }
            // else: biome not configured + fallback enabled → vanilla tree runs normally
        }
    }
}
