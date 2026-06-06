package earth.worldwind.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import earth.worldwind.WorldWindow as PlatformWorldWindow

/**
 * Hosts the WorldWind engine for Compose Multiplatform's Skia-canvas Web target (`wasmJs`).
 *
 * Skia-backed Compose/Web renders the whole UI into a single `<canvas>` and — unlike the
 * DOM-based Compose HTML used on the `js` target — it cannot embed WorldWind's own WebGL
 * `<canvas>` inside that surface. So this binding takes the only workable route on wasmJs: it
 * creates a full-window WebGL `<canvas>` in the DOM *behind* the (transparent) Compose surface
 * and lets the Compose UI overlay it. The globe and the Compose UI are wired together through
 * [WorldWindowState] exactly as on the other platforms.
 *
 * The [modifier] is accepted for API symmetry with the other targets; the globe canvas itself is
 * always full-window. The globe canvas is given the id `worldwind-canvas`. For it to be visible AND
 * interactive, the host page must (a) make the Compose surface transparent and (b) let pointer
 * events fall through to the globe canvas — i.e. `body > canvas { pointer-events: none }` plus
 * `#worldwind-canvas { pointer-events: auto }` (see the wasmJs sample's `index.html`). Apps that
 * overlay interactive Compose UI on the globe must instead re-enable pointer-events on their own
 * controls.
 */
@Composable
fun WorldWindow(
    state: WorldWindowState,
    modifier: Modifier = Modifier,
    onCreated: (PlatformWorldWindow) -> Unit = {},
) {
    DisposableEffect(state) {
        val canvas = (document.createElement("canvas") as HTMLCanvasElement).apply {
            id = "worldwind-canvas"
            style.position = "fixed"
            style.left = "0"
            style.top = "0"
            style.width = "100%"
            style.height = "100%"
            style.zIndex = "0"
            // Receive gestures even though the Compose surface sits on top; the host page must set
            // pointer-events:none on the Compose canvas so events fall through to here (see KDoc).
            style.setProperty("pointer-events", "auto")
        }
        // Insert behind everything else (Compose mounts its own canvas on top).
        document.body?.insertBefore(canvas, document.body?.firstChild)
        val wwd = PlatformWorldWindow(canvas)
        state.factory(wwd.engine)
        state.attach(wwd.engine, wwd)
        onCreated(wwd)
        onDispose {
            state.detach()
            canvas.remove()
        }
    }
    Box(modifier)
}
