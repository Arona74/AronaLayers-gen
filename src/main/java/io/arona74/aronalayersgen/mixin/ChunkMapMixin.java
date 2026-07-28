package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.injection.RTFCompat;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Shadow
    private net.minecraft.world.level.levelgen.RandomState randomState;

    @Inject(
        method = "<init>",
        at = @At("TAIL")
    )
    private void onInit(CallbackInfo ci) {
        if (randomState != null) {
            AronaLayersGen.LOGGER.info("[ChunkMap Mixin] Capturing NoiseConfig/RandomState: {}", randomState.getClass().getName());
            RandomStateHolder.setNoiseConfig(randomState);
            RandomStateHolder.setRandomState(randomState);
        }
    }

    @Inject(
        method = "close",
        at = @At("HEAD")
    )
    private void onClose(CallbackInfo ci) {
        RandomStateHolder.clear();
        RTFCompat.resetWorldState();
        AronaLayersGen.LOGGER.info("[ChunkMap Mixin] Cleared world state on chunk manager close");
    }
}
