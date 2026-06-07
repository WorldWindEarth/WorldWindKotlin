package earth.worldwind.compose

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Canvas
import org.w3c.dom.HTMLCanvasElement
import earth.worldwind.WorldWindow as PlatformWorldWindow

/**
 * Hosts the WorldWind engine inside a Compose HTML composition.
 *
 * Compose Multiplatform's Skia-backed Web target (`wasmJs`) cannot share its single underlying
 * `<canvas>` with WebGL, so this binding uses Compose HTML (DOM-based Compose) instead. Compose
 * HTML's element-attribute model is incompatible with Compose UI's `androidx.compose.ui.Modifier`,
 * which is why this overload accepts an [AttrsScope] builder rather than a `Modifier`.
 */
@Composable
fun WorldWindow(
    state: WorldWindowState,
    attrs: AttrsScope<HTMLCanvasElement>.() -> Unit = {},
    onCreated: (PlatformWorldWindow) -> Unit = {},
) {
    Canvas({
        attrs()
        ref { canvas ->
            val wwd = PlatformWorldWindow(canvas)
            state.factory(wwd.engine)
            state.attach(wwd.engine, wwd)
            onCreated(wwd)
            onDispose { state.detach() }
        }
    })
}
