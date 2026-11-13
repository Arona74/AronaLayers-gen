package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a layer block hold a decoration on its top: torches, lanterns, candles and pressure plates
 * ask {@code canSupportCenter(level, below, UP)}, which a layer's partial top fails. With
 * ground_decorations on those are drawn and shaped on the layer's surface (PlantGrounding.offset),
 * so they may stand there too. Only the upward check on a layer block changes; vanilla calls it for
 * nothing else.
 */
@Mixin(Block.class)
public abstract class LayerSupportMixin {

    @Inject(method = "canSupportCenter", at = @At("HEAD"), cancellable = true)
    private static void aronalayersgen$layerSupportsCenter(LevelReader level, BlockPos pos, Direction direction,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (direction != Direction.UP || !LayerConfig.PLANT_GROUNDING || !LayerConfig.GROUND_DECORATIONS) return;
        if (PlantGrounding.isLayerSoil(level.getBlockState(pos))) cir.setReturnValue(true);
    }
}
