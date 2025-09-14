package earth.worldwind.tutorials

import earth.worldwind.render.image.ImageSource
import java.awt.Color
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

actual class TriangleMeshImageFactory actual constructor(
    private val size: Int, private val innerRadius: Float, private val outerRadius: Float
) : ImageSource.ImageFactory {
    override suspend fun createImage(): BufferedImage? {
        val c = size / 2f - 0.5f

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val gradient = RadialGradientPaint(
            c, c, outerRadius,
            floatArrayOf(0f, 0.5f, 1f),
            arrayOf(
                Color(255, 0, 0),    // Red
                Color(0, 255, 0),    // Green
                Color(255, 0, 0)     // Red
            )
        )

        g2d.paint = gradient
        g2d.fill(Ellipse2D.Float(c - outerRadius, c - outerRadius, outerRadius * 2, outerRadius * 2))

        g2d.dispose()
        return image
    }
}