package earth.worldwind.tutorials

import earth.worldwind.draw.DrawableLines
import earth.worldwind.geom.Vec3
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.math.encodeOrientationVector

internal class RadialPickLineRenderable : AbstractRenderable("Picked point line") {
    private val endPoint = Vec3()
    private val lineColor = Color(1f, 0f, 0f, 1f)

    var lineWidth = 3.5f

    init {
        isPickEnabled = false
        isEnabled = false
    }

    fun show(endPoint: Vec3, color: Color) = apply {
        this.endPoint.copy(endPoint)
        lineColor.copy(color)
        isEnabled = true
    }

    fun hide() {
        isEnabled = false
    }

    override fun doRender(rc: RenderContext) {
        val pool = rc.getDrawablePool(DrawableLines.KEY)
        val drawable = DrawableLines.obtain(pool)
        drawable.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }
        prepareDrawable(rc, drawable)
        // Render the line after translucent meshes so its visible outer segment is not blended away.
        rc.offerShapeDrawable(drawable, 0.0)
    }

    private fun prepareDrawable(rc: RenderContext, drawable: DrawableLines) {
        val upperLeftCorner = encodeOrientationVector(-1f, 1f)
        val lowerLeftCorner = encodeOrientationVector(-1f, -1f)
        var vertexIndex = 0
        repeat(4) { cornerIndex ->
            drawable.vertexPoints[vertexIndex++] = endPoint.x.toFloat()
            drawable.vertexPoints[vertexIndex++] = endPoint.y.toFloat()
            drawable.vertexPoints[vertexIndex++] = endPoint.z.toFloat()
            drawable.vertexPoints[vertexIndex++] = if (cornerIndex % 2 == 0) upperLeftCorner else lowerLeftCorner
            drawable.vertexPoints[vertexIndex++] = 0f
        }
        repeat(4) { cornerIndex ->
            drawable.vertexPoints[vertexIndex++] = 0f
            drawable.vertexPoints[vertexIndex++] = 0f
            drawable.vertexPoints[vertexIndex++] = 0f
            drawable.vertexPoints[vertexIndex++] = if (cornerIndex % 2 == 0) upperLeftCorner else lowerLeftCorner
            drawable.vertexPoints[vertexIndex++] = 0f
        }

        drawable.mvpMatrix.copy(rc.modelviewProjection)
        drawable.color.copy(lineColor)
        drawable.opacity = rc.currentLayer.opacity
        drawable.lineWidth = lineWidth
        drawable.enableDepthTest = true
    }
}
