package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.GROUND_COVERED_BIT
import earth.worldwind.util.kgl.GL_ALWAYS
import earth.worldwind.util.kgl.GL_KEEP
import earth.worldwind.util.kgl.GL_NOTEQUAL
import earth.worldwind.util.kgl.GL_REPLACE
import earth.worldwind.util.kgl.GL_STENCIL_TEST

/** Bits 0..6 = Cesium skip-LoD subtree id (1..127). Bit 7 is [GROUND_COVERED_BIT]. */
internal const val TILE_SUBTREE_MASK = 0x7F

/** Tile-content stencil (mesh, point cloud): always writes [GROUND_COVERED_BIT]; bits 0..6
 *  either hold the subtree id (fine non-fallback) or get tested for fallback / left untouched
 *  for non-skip-LoD tiles. */
internal fun applyTileStencilState(dc: DrawContext, isFallback: Boolean, stencilId: Int) {
    dc.gl.enable(GL_STENCIL_TEST)
    when {
        // Coarse fallback: pass where bits 0..6 != stencilId; write only bit 7 on pass.
        isFallback -> {
            dc.gl.stencilFunc(GL_NOTEQUAL, stencilId or GROUND_COVERED_BIT, TILE_SUBTREE_MASK)
            dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
            dc.gl.stencilMask(GROUND_COVERED_BIT)
        }
        // Fine non-fallback: write subtree id + bit 7 in one byte.
        stencilId > 0 -> {
            dc.gl.stencilFunc(GL_ALWAYS, stencilId or GROUND_COVERED_BIT, 0xFF)
            dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
            dc.gl.stencilMask(0xFF)
        }
        // Regular tile: write only bit 7 so other subtrees' bits 0..6 stay intact.
        else -> {
            dc.gl.stencilFunc(GL_ALWAYS, GROUND_COVERED_BIT, 0xFF)
            dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
            dc.gl.stencilMask(GROUND_COVERED_BIT)
        }
    }
}

/** Restore stencil state to defaults. */
internal fun clearTileStencilState(dc: DrawContext) {
    dc.gl.disable(GL_STENCIL_TEST)
    dc.gl.stencilFunc(GL_ALWAYS, 0, 0xFF)
    dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
    dc.gl.stencilMask(0xFF)
}
