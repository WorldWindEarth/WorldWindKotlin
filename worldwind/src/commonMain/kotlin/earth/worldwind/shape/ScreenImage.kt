package earth.worldwind.shape

import earth.worldwind.draw.DrawableScreenTexture
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Vec2
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.program.BasicShaderProgram

open class ScreenImage(
    imageSource: ImageSource,
    screenOffset: Offset = Offset.bottomLeft(),
    imageOffset: Offset = Offset.bottomLeft(),
    var imageScale: Double = 1.0,
    var imageRotation: Angle = Angle.ZERO,
    color: Color = Color(1f, 1f, 1f, 1f)
) : AbstractRenderable() {
    var imageSource: ImageSource = imageSource
    var screenOffset: Offset = Offset(screenOffset)
    var imageOffset: Offset = Offset(imageOffset)
    val color: Color = Color(color)
    var opacity = 1f

    private val scratchVec = Vec2()
    private val transform = Matrix4()

    override fun doRender(rc: RenderContext) {
        val texture = rc.getTexture(imageSource) ?: return
        val tw = texture.width.toDouble()
        val th = texture.height.toDouble()
        val sw = tw * imageScale
        val sh = th * imageScale

        screenOffset.offsetForSize(rc.viewport.width.toDouble(), rc.viewport.height.toDouble(), scratchVec)
        val sx = scratchVec.x
        val sy = scratchVec.y

        imageOffset.offsetForSize(sw, sh, scratchVec)
        val ax = scratchVec.x
        val ay = scratchVec.y

        if (imageRotation.inDegrees == 0.0) {
            transform.setToIdentity()
            transform.setTranslation(sx - ax, sy - ay, 0.0)
            transform.setScale(sw, sh, 1.0)
        } else {
            // rotate around image center
            val cx = sx - ax + sw * 0.5
            val cy = sy - ay + sh * 0.5
            transform.setToTranslation(cx, cy, 0.0)
            transform.multiplyByRotation(0.0, 0.0, 1.0, imageRotation)
            transform.multiplyByTranslation(-sw * 0.5, -sh * 0.5, 0.0)
            transform.multiplyByScale(sw, sh, 1.0)
        }

        val pool = rc.getDrawablePool(DrawableScreenTexture.KEY)
        val drawable = DrawableScreenTexture.obtain(pool)
        drawable.program = rc.getShaderProgram(BasicShaderProgram.KEY) { BasicShaderProgram() }
        drawable.unitSquareTransform.copy(transform)
        drawable.color.copy(color)
        drawable.opacity = opacity * rc.currentLayer.opacity
        drawable.texture = texture
        drawable.enableDepthTest = false
        rc.offerScreenDrawable(drawable, 0.0)
    }
}
