/*
 * Adapted from Geostep (verdantlayers) 1.3, MIT License, Copyright (c) 2026 Verdant Layers contributors.
 * The full license text is in compat/dh/DhLayerMesh.java and THIRD_PARTY_NOTICES.md.
 */
package io.arona74.aronalayersgen.mixin.dh;

import io.arona74.aronalayersgen.compat.dh.DhEighths;
import io.arona74.aronalayersgen.compat.dh.DhLayerMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Packs vertex Y as whole block + eighths for sections rewritten in eighths (DhLayerMesh.packVertexY). */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder", remap = false)
public abstract class LodVertexMixin implements DhEighths {
    @Unique
    private boolean aronalayersgen$eighths;

    @Override
    public boolean aronalayersgen$eighths() {
        return aronalayersgen$eighths;
    }

    @Override
    public void aronalayersgen$eighths(boolean eighths) {
        this.aronalayersgen$eighths = eighths;
    }

    @ModifyVariable(method = "putVertex", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private short aronalayersgen$packY(short y) {
        return aronalayersgen$eighths ? DhLayerMesh.packVertexY(y) : y;
    }
}
