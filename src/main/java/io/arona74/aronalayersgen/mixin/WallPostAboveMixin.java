package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A wall reads the block on it as it stands, not as grounding lowers it.
 *
 * <p>A wall decides its post and tall sides from the bottom face of the block above's collision.
 * A decoration on a lowered wall (a lantern) is lowered with it, which moves its collision off the
 * bottom of its cell: the wall then saw nothing there and dropped its post, though the lantern
 * still stands on it. The wall is drawn and shaped from that state, lowered as a whole, so the
 * decoration is read unlowered, as if both stood on the ground.
 */
@Mixin(WallBlock.class)
public abstract class WallPostAboveMixin {

    @WrapOperation(
        method = "updateShape(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;ZZZZ)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    )
    private VoxelShape aronalayersgen$unloweredAbove(BlockState above, BlockGetter level, BlockPos pos, Operation<VoxelShape> original) {
        if (PlantGrounding.isGrounded(above)) return original.call(above, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return original.call(above, level, pos);
    }
}
