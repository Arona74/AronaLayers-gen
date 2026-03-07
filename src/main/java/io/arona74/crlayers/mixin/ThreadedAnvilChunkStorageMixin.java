package io.arona74.crlayers.mixin;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.injection.RandomStateHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture the RandomState when ThreadedAnvilChunkStorage is initialized.
 * This allows us to access RTFRandomState during chunk generation.
 */
@Mixin(ThreadedAnvilChunkStorage.class)
public class ThreadedAnvilChunkStorageMixin {

    @Shadow
    private net.minecraft.world.gen.noise.NoiseConfig noiseConfig;

    @Inject(
        method = "<init>",
        at = @At("TAIL")
    )
    private void onInit(CallbackInfo ci) {
        // The noiseConfig field is RandomState in Mojang mappings
        // Capture it for use during chunk generation
        if (noiseConfig != null) {
            CRLayers.LOGGER.info("[TACS Mixin] Capturing NoiseConfig/RandomState: {}", noiseConfig.getClass().getName());
            RandomStateHolder.setRandomState(noiseConfig);
        }
    }
}
