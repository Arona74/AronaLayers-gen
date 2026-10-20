package io.arona74.aronalayersgen.mixin.dh;

import io.arona74.aronalayersgen.compat.dh.DhEighths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/** Remembers on a section's render data whether DhLayerMesh rewrote it in eighths. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource", remap = false)
public abstract class RenderSourceMixin implements DhEighths {
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
}
