package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.minecraft.block.BlockState;
import net.minecraft.block.SaplingBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the sapling block ID before SaplingBlock.generate() removes the block and
 * calls TreeFeature.generate(). Without this, NbtTreeInjector can't distinguish which
 * sapling type triggered the grow event (the position holds AIR by then).
 */
@Mixin(SaplingBlock.class)
public class SaplingBlockGrowMixin {

    @Inject(
        method = "generate(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/random/Random;)V",
        at = @At("HEAD")
    )
    private void captureGrowSapling(ServerWorld world, BlockPos pos, BlockState state, Random random, CallbackInfo ci) {
        if (!LayerConfig.CR_NBT_TREES) return;
        NbtTreeInjector.setSaplingGrowContext(Registries.BLOCK.getId(state.getBlock()));
    }

    @Inject(
        method = "generate(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/random/Random;)V",
        at = @At("RETURN")
    )
    private void clearGrowSapling(ServerWorld world, BlockPos pos, BlockState state, Random random, CallbackInfo ci) {
        NbtTreeInjector.clearSaplingGrowContext();
    }
}
