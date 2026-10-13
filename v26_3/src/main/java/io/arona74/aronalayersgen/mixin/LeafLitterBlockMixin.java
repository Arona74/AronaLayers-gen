package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets leaf litter sit on a layer block.
 *
 * <p>Needs its own mixin even though LeafLitterBlock extends the plant base that
 * {@link PlantBlockMixin} already hooks: it overrides {@code canSurvive} and does not call super,
 * so the inherited hook never runs. It also asks a different question — {@code isFaceSturdy(UP)}
 * on the block below rather than {@code mayPlaceOn} — and a layer block answers false for that at
 * any count below 8.
 *
 * <p>Without this the litter is written during worldgen, where {@code canSurvive} is never
 * consulted, and then evicted by the first neighbour update that reaches it. Same shape of bug as
 * snow layers on packed ice.
 *
 * <p>Only exists on versions that have leaf litter (1.21.5+), which is why this is a per-version
 * mixin rather than a shared one.
 */
@Mixin(LeafLitterBlock.class)
public abstract class LeafLitterBlockMixin {

    @Inject(
        method = "canSurvive",
        at = @At("HEAD"),
        cancellable = true
    )
    private void aronalayersgen$surviveOnLayers(BlockState state, LevelReader world, BlockPos pos,
                                                CallbackInfoReturnable<Boolean> cir) {
        Boolean result = PlantPlacementHook.lookThroughLayersForSturdyFace(world, pos);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
