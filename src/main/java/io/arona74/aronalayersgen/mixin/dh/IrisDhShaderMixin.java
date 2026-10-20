/*
 * Adapted from Geostep (verdantlayers) 1.3, MIT License, Copyright (c) 2026 Verdant Layers contributors.
 * The full license text is in compat/dh/DhLayerMesh.java and THIRD_PARTY_NOTICES.md.
 */
package io.arona74.aronalayersgen.mixin.dh;

import io.arona74.aronalayersgen.compat.dh.IrisLayerShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Iris's DH terrain shader with exact layer heights decoded, when a shader pack is on (IrisLayerShader). */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class IrisDhShaderMixin {

    @Inject(method = "patchDHTerrain", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void aronalayersgen$decodeY(CallbackInfoReturnable<Map<Object, String>> cir) {
        Map<Object, String> original = cir.getReturnValue();
        if (original == null) return;
        Map<Object, String> patched = new LinkedHashMap<>(original);
        for (Map.Entry<Object, String> entry : original.entrySet()) {
            if (entry.getKey() instanceof Enum<?> type && type.name().equals("VERTEX") && entry.getValue() != null) {
                patched.put(entry.getKey(), IrisLayerShader.patch(entry.getValue()));
            }
        }
        cir.setReturnValue(patched);
    }
}
