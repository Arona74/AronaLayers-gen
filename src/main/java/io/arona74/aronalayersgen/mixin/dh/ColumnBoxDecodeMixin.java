package io.arona74.aronalayersgen.mixin.dh;

import io.arona74.aronalayersgen.compat.dh.DhLayerMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * dh_exact_layers_color_bits only (DhMixinPlugin skips this otherwise): the render data heights
 * ColumnBox reads, in eighths of a block (whole block * 8 + the eighths DhLayerMesh keeps in the colour
 * bits). Redirected at these call sites only, so DH's getters stay untouched everywhere else.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnBox", remap = false)
public abstract class ColumnBoxDecodeMixin {

    @Redirect(method = {"addBoxQuadsToBuilder", "makeAdjVerticalQuad"}, at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/util/RenderDataPointUtil;getYMin(J)S", remap = false), remap = false)
    private static short aronalayersgen$meshYMin(long data) {
        return DhLayerMesh.meshYMin(data);
    }

    @Redirect(method = {"addBoxQuadsToBuilder", "makeAdjVerticalQuad"}, at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/util/RenderDataPointUtil;getYMax(J)S", remap = false), remap = false)
    private static short aronalayersgen$meshYMax(long data) {
        return DhLayerMesh.meshYMax(data);
    }

    @Redirect(method = {"addBoxQuadsToBuilder", "makeAdjVerticalQuad"}, at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/util/RenderDataPointUtil;hasZeroHeight(J)Z", remap = false), remap = false)
    private static boolean aronalayersgen$meshZeroHeight(long data) {
        return DhLayerMesh.meshZeroHeight(data);
    }
}
