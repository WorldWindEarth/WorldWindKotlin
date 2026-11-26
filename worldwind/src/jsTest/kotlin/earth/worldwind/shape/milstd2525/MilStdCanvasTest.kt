package earth.worldwind.shape.milstd2525

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MilStdCanvasTest {
    private val bounds = Rectangle2D(0.0, 0.0, 17.0, 23.0)

    private fun canvasOf(svg: String, symbolId: String) =
        createMilStdCanvas(SVGSymbolInfo(svg, Point2D(), bounds, bounds), symbolId)

    @Test
    fun malformedSvgReturnsNull() {
        assertNull(canvasOf("<svg>", "invalid-xml"))
    }

    @Test
    fun renderFailureReturnsNull() {
        // Canvas rejects the negative radial gradient radius during rendering.
        assertNull(
            canvasOf(
                """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="30"><defs><radialGradient id="g" r="-1">""" +
                    """<stop offset="0" stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient></defs>""" +
                    """<rect width="40" height="30" fill="url(#g)"/></svg>""",
                "invalid-gradient",
            )
        )
    }

    @Test
    fun svgWaitingOnImageReturnsNull() {
        // An image loads asynchronously, so canvg is not ready when start() returns.
        assertNull(
            canvasOf(
                """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="17" height="23">""" +
                    """<image width="17" height="23" xlink:href="data:image/png;base64,""" +
                    """iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="/></svg>""",
                "waits-on-image",
            )
        )
    }

    @Test
    fun validSvgDrawsOnceWithoutMouseHandlers() {
        val canvas = assertNotNull(
            canvasOf(
                """<svg xmlns="http://www.w3.org/2000/svg" width="17" height="23"><rect width="17" height="23" fill="red"/></svg>""",
                "valid",
            )
        )
        val blank = (document.createElement("canvas") as HTMLCanvasElement).apply {
            width = 17
            height = 23
        }
        assertNotEquals(blank.toDataURL(), canvas.toDataURL())
        assertNull(canvas.onclick)
        assertNull(canvas.onmousemove)
    }
}
