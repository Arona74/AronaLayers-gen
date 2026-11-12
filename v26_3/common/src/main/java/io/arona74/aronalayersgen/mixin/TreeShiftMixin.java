package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Starts a generated tree one block higher, so the layer pass can put a layer in the cell its trunk
 * would have stood in (see LayerPlacementHelper.shiftTreeOrigin). TreeFeature.place hands its
 * origin to everything after: trunk, foliage, decorators.
 *
 * <p>26.3: TreeFeature is a record carrying its own config and place takes the origin directly,
 * so the parameter is replaced instead of FeaturePlaceContext.origin()'s result.
 */
@Mixin(TreeFeature.class)
public class TreeShiftMixin {

    private static final String PLACE = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z";

    @ModifyVariable(method = PLACE, at = @At("HEAD"), argsOnly = true)
    private BlockPos aronalayersgen$shiftOrigin(BlockPos origin, @Local(argsOnly = true) WorldGenLevel level) {
        return LayerPlacementHelper.shiftTreeOrigin(level, origin, ((TreeFeature) (Object) this).rootPlacer().isPresent());
    }

    @Inject(method = PLACE, at = @At("RETURN"))
    private void aronalayersgen$endPlacement(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                             CallbackInfoReturnable<Boolean> cir) {
        LayerPlacementHelper.endTreePlacement();
    }
}
