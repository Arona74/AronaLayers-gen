package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Puts the block a trunk placer sets under its trunk below the layer the tree stands on, instead of
 * over it (see LayerPlacementHelper.soilUnderTrunk). Every trunk placer goes through
 * placeBelowTrunkBlock (26.x's name for setDirtAt), once per trunk column.
 */
@Mixin(TrunkPlacer.class)
public abstract class TrunkDirtMixin {

    @ModifyVariable(method = "placeBelowTrunkBlock", at = @At("HEAD"), argsOnly = true)
    private static BlockPos aronalayersgen$soilUnderLayer(BlockPos pos, @Local(argsOnly = true) WorldGenLevel level) {
        return LayerPlacementHelper.soilUnderTrunk(level, pos);
    }
}
