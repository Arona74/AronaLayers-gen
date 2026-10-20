package io.arona74.aronalayersgen.mixin.dh;

import io.arona74.aronalayersgen.compat.dh.DhLayerMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DH's own terrain vertex shader, with exact layer heights decoded (DhLayerMesh.patchShader). */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader", remap = false)
public abstract class DhShaderMixin {

    @Inject(method = "loadFile", at = @At("RETURN"), cancellable = true, remap = false)
    private static void aronalayersgen$decodeY(String path, boolean absolute, CallbackInfoReturnable<String> cir) {
        if (!absolute) cir.setReturnValue(DhLayerMesh.patchShader(path, cir.getReturnValue()));
    }
}
