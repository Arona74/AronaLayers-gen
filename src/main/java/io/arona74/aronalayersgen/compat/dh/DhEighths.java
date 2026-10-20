package io.arona74.aronalayersgen.compat.dh;

/**
 * Implemented on DH's ColumnRenderSource (a section's render data) and LodQuadBuilder (its mesh
 * builder) by mixins: whether the section was rewritten in eighths of a block (DhLayerMesh).
 */
public interface DhEighths {
    boolean aronalayersgen$eighths();

    void aronalayersgen$eighths(boolean eighths);
}
