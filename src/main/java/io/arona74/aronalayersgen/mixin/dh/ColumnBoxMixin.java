/*
 * Adapted from Geostep (verdantlayers) 1.3, MIT License, Copyright (c) 2026 Verdant Layers contributors.
 * The full license text is in compat/dh/DhLayerMesh.java and THIRD_PARTY_NOTICES.md.
 */
package io.arona74.aronalayersgen.mixin.dh;

import io.arona74.aronalayersgen.compat.dh.DhLayerMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The cave-culling height compared against eighth-unit faces, scaled to eighths as well. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnBox", remap = false)
public abstract class ColumnBoxMixin {

    @ModifyArg(
        method = "addBoxQuadsToBuilder",
        at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/ColumnBox;makeAdjVerticalQuad(Lcom/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodQuadBuilder;Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;Lcom/seibel/distanthorizons/core/dataObjects/render/columnViews/ColumnRenderView;[SZILcom/seibel/distanthorizons/core/enums/EDhDirection;SSSSSIBB)V", remap = false),
        index = 6,
        remap = false
    )
    private static int aronalayersgen$cullingHeight(int value) {
        return DhLayerMesh.BUILDING_EIGHTHS.get() && value != Integer.MIN_VALUE ? value * 8 : value;
    }
}
