package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture the RandomState when ThreadedAnvilChunkStorage is initialized.
 * Allows access to RTFRandomState during chunk generation.
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
        if (noiseConfig != null) {
            AronaLayersGen.LOGGER.info("[TACS Mixin] Capturing NoiseConfig/RandomState: {}", noiseConfig.getClass().getName());
            RandomStateHolder.setNoiseConfig(noiseConfig);
            RandomStateHolder.setRandomState(noiseConfig);
        }
    }
}
