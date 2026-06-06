package earth.worldwind.compose.samples

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import earth.worldwind.compose.WorldWindow
import earth.worldwind.compose.rememberWorldWindowState
import kotlinx.browser.document

/**
 * wasmJs (Skia-canvas Compose/Web) entry point. Compose mounts its surface on the page body and
 * the [WorldWindow] binding hosts the globe on a full-window WebGL canvas behind it. For the globe
 * to show through, the page's Compose canvas must be transparent (see the module's index.html).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { SampleApp() }
}

@Composable
private fun SampleApp() {
    val state = rememberWorldWindowState { engine -> engine.setupBasicGlobe() }
    WorldWindow(state = state)
}
