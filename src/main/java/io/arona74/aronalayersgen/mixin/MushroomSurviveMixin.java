package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.PlantPlacementHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.MushroomBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a small mushroom stand on a layer block. MushroomBlock overrides canSurvive without calling
 * super, so PlantBlockMixin never runs for it, and both of its tests (MUSHROOM_GROW_BLOCK below,
 * or dark enough and a solid block below) fail on a layer. It reads its soil once, as
 * {@code pos.below()}, and hands that position to both tests; moving it under the layer block
 * makes them look at the real ground, while the light check stays at the mushroom itself.
 */
@Mixin(MushroomBlock.class)
public abstract class MushroomSurviveMixin {

    @ModifyExpressionValue(
        method = "canSurvive",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;", ordinal = 0)
    )
    private BlockPos aronalayersgen$soilUnderLayer(BlockPos query, @Local(argsOnly = true) LevelReader level,
                                                   @Local(argsOnly = true) BlockPos pos) {
        return PlantPlacementHook.lookThroughLayerForColumnPlant(level, pos, query);
    }
}
