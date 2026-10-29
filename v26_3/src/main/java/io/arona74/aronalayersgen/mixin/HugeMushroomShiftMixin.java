package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Huge mushrooms get the trees' treatment (see TreeShiftMixin): a generated one starts one block
 * higher, so the layer pass can fill the cell its stem would have stood in.
 *
 * <p>Unlike a tree, the feature checks its own soil, the block directly under the stem (tested with
 * canPlaceOn against {@code pos.below()}), so that position looks through the empty cell, or through
 * a layer when bone meal grows a mushroom standing on one (see LayerPlacementHelper.mushroomSoil).
 * The first below() in isValidPosition is the soil.
 *
 * <p>26.3: AbstractHugeMushroomFeature is an interface and place / isValidPosition are its default
 * methods, which the red and brown features inherit, so this is an interface mixin.
 */
@Mixin(AbstractHugeMushroomFeature.class)
public interface HugeMushroomShiftMixin {

    @ModifyVariable(
        method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"), argsOnly = true
    )
    private BlockPos aronalayersgen$shiftOrigin(BlockPos origin, @Local(argsOnly = true) WorldGenLevel level) {
        return LayerPlacementHelper.shiftTreeOrigin(level, origin, false);
    }

    @Inject(
        method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("RETURN")
    )
    private void aronalayersgen$endPlacement(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                             CallbackInfoReturnable<Boolean> cir) {
        LayerPlacementHelper.endTreePlacement();
    }

    @ModifyExpressionValue(
        method = "isValidPosition",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;", ordinal = 0)
    )
    private BlockPos aronalayersgen$soilThroughLayer(BlockPos soil, @Local(argsOnly = true) WorldGenLevel level) {
        return LayerPlacementHelper.mushroomSoil(level, soil);
    }
}
