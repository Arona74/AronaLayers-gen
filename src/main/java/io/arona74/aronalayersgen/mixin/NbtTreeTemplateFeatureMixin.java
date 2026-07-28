package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RTF-compatible intercept: cancels TemplateFeature.place() and substitutes a CR NBT tree.
 * Only loaded when ReTerraForged is present (see AronaLayersMixinPlugin).
 *
 * RTF replaces most vanilla PlacedFeatures with TemplateFeature-based equivalents, so
 * NbtTreeFeatureMixin on TreeFeature.generate() never fires for those biomes. This mixin
 * targets TemplateFeature at the same HEAD position — before RTF places any blocks —
 * giving identical behaviour to the vanilla intercept.
 *
 * @Pseudo suppresses the compile-time "target not found" error when RTF is absent.
 * AronaLayersMixinPlugin additionally gates shouldApplyMixin() so the mixin is never
 * applied at runtime without RTF.
 *
 * The @Inject uses the raw intermediary descriptor (method_13151 / class_5821) with
 * remap=false. When remap=false is set on the @Mixin class, Loom remaps the parameter
 * class in the descriptor but NOT the method name, leaving "generate" as a literal that
 * fails to match RTF's bytecode. Using the intermediary form directly sidesteps this.
 */
@Pseudo
@Mixin(targets = "raccoonman.reterraforged.world.worldgen.feature.template.TemplateFeature", remap = false)
public class NbtTreeTemplateFeatureMixin {

    @Inject(
        method = "method_13151(Lnet/minecraft/class_5821;)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void onGenerate(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.CR_NBT_TREES) return;
        if (LayerConfig.logNbtTrees())
            AronaLayersGen.LOGGER.info("[NbtTrees] rtf-mixin fired pos={}", context.origin());

        // RTF's TemplateFeature is only called during worldgen, never for sapling growing.
        // Cancel and defer, but only if this biome is configured (or vanilla_fallback=false).
        if (NbtTreeInjector.willHandleWorldgenTree(context.level(), context.origin())) {
            NbtTreeInjector.queueWorldgenTree(context.origin());
            cir.setReturnValue(true);
        }
        // else: biome not configured + fallback enabled → RTF places its own tree normally
    }
}
