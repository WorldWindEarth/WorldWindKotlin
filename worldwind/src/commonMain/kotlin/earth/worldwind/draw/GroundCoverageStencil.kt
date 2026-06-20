package earth.worldwind.draw

/** Bit 7 of the stencil byte = "covered by a ground-group drawable" (e.g. 3D-Tile mesh).
 *  Written by ground-group draws that participate in terrain composition, tested by
 *  [BasicDrawableTerrain.drawTriangles] so terrain skips pixels claimed by mesh content.
 *  Bits 0..6 stay free for subsystem-specific use (e.g. Cesium skip-LoD subtree ids). */
const val GROUND_COVERED_BIT = 0x80
