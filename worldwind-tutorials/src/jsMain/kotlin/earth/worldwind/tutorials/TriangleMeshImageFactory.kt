package earth.worldwind.tutorials

import earth.worldwind.render.image.ImageSource
import kotlinx.browser.document
import org.khronos.webgl.TexImageSource
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.PI

actual class TriangleMeshImageFactory actual constructor(
    private val size: Int, private val innerRadius: Float, private val outerRadius: Float
) : ImageSource.ImageFactory {
    override suspend fun createImage(): TexImageSource? {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val ctx2d = canvas.getContext("2d") as CanvasRenderingContext2D
        val c = size / 2.0 - 0.5

        canvas.width = size
        canvas.height = size

        val gradient = ctx2d.createRadialGradient(c, c, innerRadius.toDouble(), c, c, outerRadius.toDouble())
        gradient.addColorStop(0.0, "rgb(255, 0, 0)")
        gradient.addColorStop(0.5, "rgb(0, 255, 0)")
        gradient.addColorStop(1.0, "rgb(255, 0, 0)")

        ctx2d.fillStyle = gradient
        ctx2d.arc(c, c, outerRadius.toDouble(), 0.0, 2.0 * PI, false)
        ctx2d.fill()

        return canvas
    }
}