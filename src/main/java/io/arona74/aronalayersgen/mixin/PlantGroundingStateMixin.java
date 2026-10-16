package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.arona74.aronalayersgen.grounding.GroundedState;
import io.arona74.aronalayersgen.grounding.GroundingFlags;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Moves the outline and collision shapes of grounded plants down with them (see PlantGrounding),
 * so a lowered plant is selected, outlined and collided with where it is drawn.
 *
 * <p>These methods run for every block, all the time — entity movement alone queries collision
 * shapes by the hundred per tick — so the hooks are MixinExtras value modifiers (no callback
 * allocation) and the grounded-or-not answer is cached on the state, leaving non-plants with one
 * field read and a compare.
 *
 * <p>Hooked points:
 * <ul>
 *   <li>{@code getShape(level, pos, context)}: the two-argument overload delegates here.</li>
 *   <li>{@code getCollisionShape(level, pos, context)}.</li>
 *   <li>The cached collision shape read inside {@code getCollisionShape(level, pos)}. That overload
 *       returns the state's cached shape when it has one and only falls through to the context
 *       overload when it doesn't, so hooking its return as well would move the uncached path twice.</li>
 * </ul>
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PlantGroundingStateMixin implements GroundedState {

    @Unique
    private byte aronalayersgen$grounded;

    @Unique
    private int aronalayersgen$groundedEpoch;

    @Override
    public boolean aronalayersgen$isGrounded() {
        byte grounded = this.aronalayersgen$grounded;
        int epoch = GroundingFlags.epoch;
        if (grounded != PlantGrounding.UNKNOWN && this.aronalayersgen$groundedEpoch == epoch) {
            return grounded == PlantGrounding.YES;
        }
        // Not before onInitialize (see GroundingFlags), and not from inside a classification.
        if (!GroundingFlags.ready || PlantGrounding.isClassifying()) return false;
        grounded = PlantGrounding.classify((BlockState) (Object) this);
        if (grounded == PlantGrounding.UNKNOWN) return false;
        this.aronalayersgen$grounded = grounded;
        this.aronalayersgen$groundedEpoch = epoch;
        return grounded == PlantGrounding.YES;
    }

    @ModifyReturnValue(
        method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN")
    )
    private VoxelShape aronalayersgen$groundOutline(VoxelShape shape, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!aronalayersgen$isGrounded()) return shape;
        return PlantGrounding.lowerShape((BlockState) (Object) this, level, pos, shape);
    }

    @ModifyReturnValue(
        method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN")
    )
    private VoxelShape aronalayersgen$groundCollision(VoxelShape shape, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!aronalayersgen$isGrounded()) return shape;
        return PlantGrounding.lowerShape((BlockState) (Object) this, level, pos, shape);
    }

    @ModifyExpressionValue(
        method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache;collisionShape:Lnet/minecraft/world/phys/shapes/VoxelShape;"
        )
    )
    private VoxelShape aronalayersgen$groundCachedCollision(VoxelShape shape, BlockGetter level, BlockPos pos) {
        if (!aronalayersgen$isGrounded()) return shape;
        return PlantGrounding.lowerShape((BlockState) (Object) this, level, pos, shape);
    }
}
