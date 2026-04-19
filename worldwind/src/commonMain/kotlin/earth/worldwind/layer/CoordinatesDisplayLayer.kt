package earth.worldwind.layer

import earth.worldwind.draw.DrawableScreenTexture
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.shape.TextAttributes
import earth.worldwind.util.format.format
import kotlin.math.abs
import kotlin.math.roundToInt

class CoordinatesDisplayLayer : AbstractLayer("Coordinates") {
    override var isPickEnabled = false

    private val textAttrs = TextAttributes().apply {
        isDepthTest = false
        isOutlineEnabled = true
        outlineWidth = 2f
    }

    override fun doRender(rc: RenderContext) {
        val vw = rc.viewport.width.toDouble()
        val d = rc.densityFactor.toDouble()

        val lookAt = rc.lookAtPosition
        val camera = rc.camera

        val eyeAlt = camera.position.altitude
        val eyeText = "Eye  " + formatAltitude(eyeAlt)

        // On large viewports show all 4 items along the bottom center; otherwise top-left
        val large = vw > 650 * d
        val baseX: Double
        val baseY: Double
        val xUnits: OffsetMode
        val yUnits: OffsetMode
        if (large) {
            baseX = vw / 2.0 - 185.0 * d
            baseY = 14.0 * d
            xUnits = OffsetMode.PIXELS
            yUnits = OffsetMode.PIXELS
        } else {
            baseX = 64.0 * d
            baseY = 5.0 * d
            xUnits = OffsetMode.PIXELS
            yUnits = OffsetMode.INSET_PIXELS
        }

        var x = baseX
        val latStr = lookAt?.let { formatLatitude(it.latitude) }
        val lonStr = lookAt?.let { formatLongitude(it.longitude) }
        val elevStr = lookAt?.let { formatAltitude(it.altitude) }

        renderText(latStr ?: "---", x, baseY, xUnits, yUnits, large, rc)
        x += 80.0 * d
        renderText(lonStr ?: "---", x, baseY, xUnits, yUnits, large, rc)
        x += 80.0 * d
        if (!large || vw > 500 * d) {
            renderText(elevStr ?: "---", x, baseY, xUnits, yUnits, large, rc)
            x += 130.0 * d
        }
        renderText(eyeText, x, baseY, xUnits, yUnits, large, rc)
    }

    private fun renderText(
        text: String, screenX: Double, screenY: Double,
        xUnits: OffsetMode, yUnits: OffsetMode,
        alignRight: Boolean, rc: RenderContext
    ) {
        val texture = rc.getText(text, textAttrs) ?: return
        val tw = texture.width.toDouble()
        val th = texture.height.toDouble()

        val sx = when (xUnits) {
            OffsetMode.INSET_PIXELS -> rc.viewport.width - screenX
            else -> screenX
        }
        val sy = when (yUnits) {
            OffsetMode.INSET_PIXELS -> rc.viewport.height - screenY
            else -> screenY
        }

        val ax = if (alignRight) tw else 0.0

        val transform = Matrix4()
        transform.setToIdentity()
        transform.setTranslation(sx - ax, sy, 0.0)
        transform.setScale(tw, th, 1.0)

        val pool = rc.getDrawablePool(DrawableScreenTexture.KEY)
        val drawable = DrawableScreenTexture.obtain(pool)
        drawable.program = rc.getShaderProgram(BasicShaderProgram.KEY) { BasicShaderProgram() }
        drawable.unitSquareTransform.copy(transform)
        drawable.color.set(1f, 1f, 1f, 1f)
        drawable.opacity = rc.currentLayer.opacity
        drawable.texture = texture
        drawable.enableDepthTest = false
        rc.offerScreenDrawable(drawable, 0.0)
    }

    private fun formatLatitude(angle: Angle): String {
        val deg = abs(angle.inDegrees)
        val suffix = if (angle.inDegrees < 0) "°S" else "°N"
        return "%.2f%s".format(deg, suffix)
    }

    private fun formatLongitude(angle: Angle): String {
        val deg = abs(angle.inDegrees)
        val suffix = if (angle.inDegrees < 0) "°W" else "°E"
        return "%.2f%s".format(deg, suffix)
    }

    private fun formatAltitude(meters: Double): String {
        return if (meters >= 1000.0) "${"%.1f".format(meters / 1000.0)} km"
        else "${meters.roundToInt()} m"
    }
}
