package io.arona74.aronalayersgen.block;

import net.minecraft.block.SnowBlock;

/**
 * A plain layered block (1–8 layers, LAYERS property, height-based collision) that reuses
 * vanilla snow-layer mechanics but with an arbitrary texture. Used for the mod's own native
 * layer blocks (e.g. deepslate_layer, moss_layer) so surfaces whose backend has no matching
 * layer block still get one.
 *
 * <p>No 8→full-block conversion and no {@code onBlockAdded} override: at layers=8 the block is
 * already a full-height cube, and staying a layer block keeps placement cheap (avoids the
 * cascading setBlockState the powder-snow conversion needs to guard against during worldgen).
 */
public class LayerBlock extends SnowBlock {

    public LayerBlock(Settings settings) {
        super(settings);
    }
}
