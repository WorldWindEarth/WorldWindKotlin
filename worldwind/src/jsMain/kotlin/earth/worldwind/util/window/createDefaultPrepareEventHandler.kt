package earth.worldwind.util.window

import org.w3c.dom.HTMLCanvasElement

fun createDefaultPrepareEventHandler(canvas: HTMLCanvasElement): PrepareEventHandler {
    // This check will false for technically valid canvas from children window
    return if (canvas is HTMLCanvasElement) prepareEventVoid  else prepareEventDefault
}
