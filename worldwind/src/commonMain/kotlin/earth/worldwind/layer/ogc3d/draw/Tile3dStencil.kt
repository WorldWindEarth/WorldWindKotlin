package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.GL_ALWAYS
import earth.worldwind.util.kgl.GL_KEEP
import earth.worldwind.util.kgl.GL_NOTEQUAL
import earth.worldwind.util.kgl.GL_REPLACE
import earth.worldwind.util.kgl.GL_STENCIL_TEST

/** True when the tile participates in a fallback subtree and needs stencil masking. */
internal fun useTileStencil(stencilId: Int): Boolean = stencilId > 0

/** Configure stencil state for the Cesium skip-LoD masking pass. Fine tiles WRITE their
 *  [stencilId]; fallback (coarse) tiles draw only where the stencil is unclaimed by a
 *  finer descendant of the same subtree. */
internal fun applyTileStencilState(dc: DrawContext, isFallback: Boolean, stencilId: Int) {
    dc.gl.enable(GL_STENCIL_TEST)
    if (isFallback) {
        dc.gl.stencilFunc(GL_NOTEQUAL, stencilId, 0xFF)
        dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
    } else {
        dc.gl.stencilFunc(GL_ALWAYS, stencilId, 0xFF)
        dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
    }
}

/** Restore stencil state to "off" so subsequent drawables aren't affected by leftover
 *  func/op bindings even when they don't toggle [GL_STENCIL_TEST] themselves. */
internal fun clearTileStencilState(dc: DrawContext) {
    dc.gl.disable(GL_STENCIL_TEST)
    dc.gl.stencilFunc(GL_ALWAYS, 0, 0xFF)
    dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
}
