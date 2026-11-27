package earth.worldwind.tutorials

import earth.worldwind.draw.DrawableScreenTexture
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.geom.Vec2
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.shape.TextAttributes

/**
 * A line of text fixed on the screen, like a [earth.worldwind.shape.ScreenImage] showing text:
 * placed at [screenOffset] in the viewport, anchored at its own [textOffset]. By default 12 pixels
 * in from the top left corner, white with a dark outline so it reads over any imagery.
 */
class ScreenText(
    var text: String = "",
    var screenOffset: Offset = Offset(OffsetMode.PIXELS, 12.0, OffsetMode.INSET_PIXELS, 12.0),
    var textOffset: Offset = Offset.topLeft(),
    val attributes: TextAttributes = TextAttributes().apply {
        textColor = Color(1f, 1f, 1f, 1f)
        outlineColor = Color(0f, 0f, 0f, 1f)
        isOutlineEnabled = true
    },
) : AbstractRenderable("Screen text") {
    private val scratch = Vec2()
    private val transform = Matrix4()

    init {
        isPickEnabled = false
    }

    override fun doRender(rc: RenderContext) {
        if (text.isEmpty() || rc.isPickMode) return
        val texture = rc.getText(text, attributes) ?: return
        val w = texture.width.toDouble()
        val h = texture.height.toDouble()
        screenOffset.offsetForSize(rc.viewport.width.toDouble(), rc.viewport.height.toDouble(), scratch)
        val sx = scratch.x
        val sy = scratch.y
        textOffset.offsetForSize(w, h, scratch)
        transform.setToIdentity()
        transform.setTranslation(sx - scratch.x, sy - scratch.y, 0.0)
        transform.setScale(w, h, 1.0)
        val drawable = DrawableScreenTexture.obtain(rc.getDrawablePool(DrawableScreenTexture.KEY))
        drawable.program = rc.getShaderProgram { BasicShaderProgram() }
        drawable.unitSquareTransform.copy(transform)
        drawable.color.set(1f, 1f, 1f, 1f)
        drawable.opacity = rc.currentLayer.opacity
        drawable.texture = texture
        drawable.enableDepthTest = false
        drawable.enableDepthWrite = false
        rc.offerScreenDrawable(drawable, 0.0)
    }
}
