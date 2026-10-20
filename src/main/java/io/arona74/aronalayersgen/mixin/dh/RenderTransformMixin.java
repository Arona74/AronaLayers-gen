/*
 * Adapted from Geostep (verdantlayers) 1.3, MIT License, Copyright (c) 2026 Verdant Layers contributors.
 * The full license text is in compat/dh/DhLayerMesh.java and THIRD_PARTY_NOTICES.md.
 */
package io.arona74.aronalayersgen.mixin.dh;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.arona74.aronalayersgen.compat.dh.DhLayerMesh;
import io.arona74.aronalayersgen.compat.dh.DhStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewrites each freshly built section of render data in eighths of a block (DhLayerMesh.refine).
 * A plain RETURN inject rather than a method wrap: 1.20.1's Fabric Loader bundles a MixinExtras
 * without @WrapMethod.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer", remap = false)
public abstract class RenderTransformMixin {

    @Inject(method = "transformFullDataToRenderSource", at = @At("HEAD"), remap = false)
    private static void aronalayersgen$timeStart(CallbackInfoReturnable<ColumnRenderSource> cir) {
        DhStats.STARTS.get()[DhStats.SLOT_TRANSFORM] = System.nanoTime();
    }

    @Inject(method = "transformFullDataToRenderSource", at = @At("RETURN"), cancellable = true, remap = false)
    private static void aronalayersgen$refine(FullDataSourceV2 full, IClientLevelWrapper level,
                                              CallbackInfoReturnable<ColumnRenderSource> cir) {
        DhStats.DH_TRANSFORM.add(DhStats.STARTS.get()[DhStats.SLOT_TRANSFORM]);
        ColumnRenderSource source = cir.getReturnValue();
        if (full == null || level == null || source == null || DhStats.skipRefine) return;
        long start = System.nanoTime();
        try {
            cir.setReturnValue(DhLayerMesh.refine(full, level, source));
            DhStats.REFINE.add(start);
        } catch (RuntimeException | Error failure) {
            DhStats.REFINE.fail();
            source.close();
            throw failure;
        }
    }
}
