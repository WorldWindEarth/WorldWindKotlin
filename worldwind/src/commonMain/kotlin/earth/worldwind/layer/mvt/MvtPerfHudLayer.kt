package earth.worldwind.layer.mvt

import earth.worldwind.draw.DrawableScreenTexture
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.shape.TextAttributes

/**
 * Diagnostic overlay that reports the [target] layer's tile-cache and schema state. Useful
 * during MVT style/source tuning — drop it next to the [MvtVectorLayer] in the engine's
 * layer list and the corner of the viewport reports:
 *
 *   - `tiles loaded / pending / backoff`
 *   - `schema = <detected>`
 *   - frame count (rolling, resets on overflow)
 *
 * Renders via [DrawableScreenTexture] in the bottom-left at logical-pixel offsets scaled by
 * [RenderContext.densityFactor]. No collision with the engine's other overlays; if you have
 * the [earth.worldwind.layer.CoordinatesDisplayLayer] active too, raise [yOffsetDp] to keep
 * lines from overlapping.
 */
class MvtPerfHudLayer(
    private val target: MvtVectorLayer,
    /** Bottom-left X offset in density-independent pixels. */
    private val xOffsetDp: Double = 8.0,
    /** Bottom-left Y offset in density-independent pixels. */
    private val yOffsetDp: Double = 48.0,
    displayName: String = "MVT Perf HUD",
) : AbstractLayer(displayName) {

    override var isPickEnabled = false

    private val textAttrs = TextAttributes().apply {
        isDepthTest = false
        isOutlineEnabled = true
        outlineWidth = 2f
        textColor.set(1f, 1f, 1f, 1f)
        outlineColor.set(0f, 0f, 0f, 1f)
    }

    // Reused across frames — render path mutates and re-uploads via the screen drawable, so
    // a single instance is safe.
    private val scratchTransform = Matrix4()

    override fun doRender(rc: RenderContext) {
        val d = rc.densityFactor.toDouble()
        val lineHeight = 16.0 * d
        var y = yOffsetDp * d
        // Text-cache keyed text content is rendered to a texture; keeping these strings
        // STABLE across frames lets [rc.getText] hit the cache instead of allocating a fresh
        // ImageTexture each render.
        val line1 = "MVT: tiles loaded=${target.loadedTileCount} pending=${target.pendingTileCount} backoff=${target.backoffTileCount}"
        val line2 = "schema=${target.detectedSchema ?: "?"}"
        renderLine(rc, line1, xOffsetDp * d, y); y += lineHeight
        renderLine(rc, line2, xOffsetDp * d, y)
    }

    private fun renderLine(rc: RenderContext, text: String, screenX: Double, screenY: Double) {
        val texture = rc.getText(text, textAttrs) ?: return
        val tw = texture.width.toDouble()
        val th = texture.height.toDouble()
        scratchTransform.setTranslation(screenX, screenY, 0.0)
        scratchTransform.setScale(tw, th, 1.0)
        val pool = rc.getDrawablePool(DrawableScreenTexture.KEY)
        val drawable = DrawableScreenTexture.obtain(pool)
        drawable.program = rc.getShaderProgram { BasicShaderProgram() }
        drawable.unitSquareTransform.copy(scratchTransform)
        drawable.color.set(1f, 1f, 1f, 1f)
        drawable.opacity = rc.currentLayer.opacity
        drawable.texture = texture
        drawable.enableDepthTest = false
        drawable.enableDepthWrite = false
        rc.offerScreenDrawable(drawable, 0.0)
    }
}
