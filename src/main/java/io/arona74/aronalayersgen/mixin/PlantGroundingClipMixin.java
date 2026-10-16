package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.grounding.GroundingFlags;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the look ray hit the part of a grounded plant that reaches down into the layer block's
 * space. The traversal only asks each cell about its own block, so without this a plant lowered
 * onto a thin layer can only be targeted through its upper part. The logic is in PlantGrounding.
 */
@Mixin(BlockGetter.class)
public interface PlantGroundingClipMixin {

    @Inject(method = "clip", at = @At("RETURN"), cancellable = true)
    private void aronalayersgen$hitLoweredPlants(ClipContext context, CallbackInfoReturnable<BlockHitResult> cir) {
        if (!GroundingFlags.ready) return;
        BlockHitResult nearer = PlantGrounding.clipLowered((BlockGetter) this, context, cir.getReturnValue());
        if (nearer != null) {
            cir.setReturnValue(nearer);
        }
    }
}
