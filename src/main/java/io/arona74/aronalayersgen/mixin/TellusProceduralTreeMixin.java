package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tellus-compatible intercept: cancels TellusProceduralTreeGenerator.place() and substitutes a
 * CR NBT tree — the same "intercept at the source" approach as {@link NbtTreeFeatureMixin} on
 * vanilla TreeFeature, but for Tellus 0.8.3's custom-tree generator (enabled by its customTrees
 * option), which places trees procedurally and never goes through vanilla TreeFeature.
 *
 * <p>Targets the 6-arg {@code place(WorldGenLevel, BlockPos, Holder&lt;Biome&gt;, ResolveEcoregion,
 * CanopySample, long)} — the real implementation both overloads funnel into, and the one
 * EarthChunkGenerator.placeTrees calls. {@code @Inject} requires the handler to declare the
 * target's full parameter list, so all six are present; the two Tellus-only types (ResolveEcoregion,
 * CanopySample) are declared as {@code @Coerce Object} since they are not on our classpath. Only
 * level and ground are used; the biome is re-derived from the level inside
 * {@link NbtTreeInjector#willHandleWorldgenTree}.
 *
 * <p>Foreign-mod mixin: {@code @Pseudo} + {@code remap=false} and a raw descriptor whose MC types
 * use intermediary names (class_5281 = WorldGenLevel, class_2338 = BlockPos, class_6880 = Holder)
 * while Tellus's own types keep their real internal names — mirroring {@link NbtTreeTemplateFeatureMixin}.
 * {@link AronaLayersMixinPlugin} gates this so it is only applied when Tellus is present.
 *
 * <p>This intercept is the sole mechanism for converting Tellus custom trees — there is no post-hoc
 * scan fallback. Trees it does not handle are left as Tellus placed them (see {@link NbtTreeInjector}).
 */
@Pseudo
@Mixin(targets = "com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator", remap = false)
public class TellusProceduralTreeMixin {

    @Inject(
        method = "place(Lnet/minecraft/class_5281;Lnet/minecraft/class_2338;Lnet/minecraft/class_6880;Lcom/yucareux/tellus/world/data/resolve/ResolveEcoregion;Lcom/yucareux/tellus/world/data/canopy/TellusCanopyHeightSource$CanopySample;J)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void onProceduralPlace(WorldGenLevel level, BlockPos ground, Holder<?> biome,
                                          @Coerce Object ecoregion, @Coerce Object canopy, long seed,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.CR_NBT_TREES) return;
        BlockPos origin = ground.above();
        if (LayerConfig.logNbtTrees())
            AronaLayersGen.LOGGER.info("[NbtTrees] tellus-procedural-mixin fired pos={}", origin);

        // Worldgen only (never sapling growth): cancel Tellus's tree and defer our CR tree, but
        // only when this biome is configured (or vanilla_fallback=false). Otherwise let Tellus
        // place its own procedural tree.
        if (NbtTreeInjector.willHandleWorldgenTree(level, origin)) {
            NbtTreeInjector.queueWorldgenTree(origin);
            cir.setReturnValue(false);
        }
    }
}
