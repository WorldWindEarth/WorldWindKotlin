package earth.worldwind.shape.milstd2525

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MilStdCanvasTest {
    @Test
    fun malformedSvgReturnsCanvasWithImageBounds() {
        assertFallbackCanvas("<svg>", "invalid-xml")
    }

    @Test
    fun renderFailureClearsCanvasAndRestoresImageBounds() {
        // Canvas rejects the negative radial gradient radius during rendering, after canvg resizes the canvas.
        assertFallbackCanvas(
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="40" height="30">
                <defs>
                    <radialGradient id="g" r="-1">
                        <stop offset="0" stop-color="red"/>
                        <stop offset="1" stop-color="blue"/>
                    </radialGradient>
                </defs>
                <rect width="40" height="30" fill="red"/>
                <rect width="40" height="30" fill="url(#g)"/>
            </svg>
            """.trimIndent(),
            "invalid-gradient",
        )
    }

    @Test
    fun validSvgDrawsOnceWithoutMouseHandlers() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="17" height="23"><rect width="17" height="23" fill="red"/></svg>"""
        val bounds = Rectangle2D(0.0, 0.0, 17.0, 23.0)

        val canvas = createMilStdCanvas(SVGSymbolInfo(svg, Point2D(), bounds, bounds), "valid")

        assertNotEquals(blankDataUrl(), canvas.toDataURL())
        assertNull(canvas.onclick)
        assertNull(canvas.onmousemove)
    }

    @Test
    fun svgWaitingOnImageReturnsBlankCanvas() {
        // An image loads asynchronously, so canvg is not ready when start() returns.
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="
        assertFallbackCanvas(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="17" height="23">""" +
                """<image width="17" height="23" xlink:href="data:image/png;base64,$png"/></svg>""",
            "waits-on-image",
        )
    }

    private fun assertFallbackCanvas(svg: String, symbolId: String) {
        val bounds = Rectangle2D(0.0, 0.0, 17.0, 23.0)
        val info = SVGSymbolInfo(svg, Point2D(), bounds, bounds)

        val canvas = createMilStdCanvas(info, symbolId)

        assertEquals(17, canvas.width)
        assertEquals(23, canvas.height)
        assertEquals("", canvas.style.getPropertyValue("width"))
        assertEquals("", canvas.style.getPropertyValue("height"))

        assertEquals(blankDataUrl(), canvas.toDataURL())
    }

    private fun blankDataUrl() = (document.createElement("canvas") as HTMLCanvasElement).apply {
        width = 17
        height = 23
    }.toDataURL()
}
