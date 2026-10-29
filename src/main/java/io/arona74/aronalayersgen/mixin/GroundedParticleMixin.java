package io.arona74.aronalayersgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Moves the ambient particles of a grounded block down with it: a torch's flame, a candle's.
 * Blocks spawn those from animateTick at fixed heights in their cell, so a lowered torch would
 * burn above its own wick.
 *
 * <p>The drop is held while the block's animateTick runs and applied to every particle added in
 * the meantime. Both run on the client thread, from ClientLevel.doAnimateTick, so a plain field
 * is enough.
 */
@Mixin(ClientLevel.class)
public abstract class GroundedParticleMixin {

    @Unique
    private static double aronalayersgen$particleDrop;

    @WrapOperation(
        method = "doAnimateTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V")
    )
    private void aronalayersgen$lowerParticles(Block block, BlockState state, Level level, BlockPos pos, RandomSource random,
                                               Operation<Void> original) {
        float drop = PlantGrounding.isGrounded(state) ? PlantGrounding.offset(state, level, pos) : 0f;
        if (drop == 0f) {
            original.call(block, state, level, pos, random);
            return;
        }
        aronalayersgen$particleDrop = drop;
        try {
            original.call(block, state, level, pos, random);
        } finally {
            aronalayersgen$particleDrop = 0.0;
        }
    }

    @ModifyVariable(
        method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",
        at = @At("HEAD"), ordinal = 1, argsOnly = true
    )
    private double aronalayersgen$lowerParticle(double y) {
        return y + aronalayersgen$particleDrop;
    }
}
