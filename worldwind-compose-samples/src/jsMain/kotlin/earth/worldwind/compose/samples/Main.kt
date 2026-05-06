package earth.worldwind.compose.samples

import androidx.compose.runtime.Composable
import earth.worldwind.compose.WorldWindow
import earth.worldwind.compose.rememberWorldWindowState
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") { SampleApp() }
}

@Composable
private fun SampleApp() {
    val state = rememberWorldWindowState { engine -> engine.setupBasicGlobe() }
    WorldWindow(state = state, attrs = {
        style {
            width(100.percent)
            height(100.percent)
        }
    })
}
