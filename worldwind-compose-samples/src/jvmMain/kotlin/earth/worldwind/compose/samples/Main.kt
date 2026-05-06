package earth.worldwind.compose.samples

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import earth.worldwind.compose.WorldWindow
import earth.worldwind.compose.rememberWorldWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "WorldWind Compose Sample",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        SampleApp()
    }
}

@Composable
private fun SampleApp() {
    val state = rememberWorldWindowState { engine -> engine.setupBasicGlobe() }
    WorldWindow(state = state, modifier = Modifier.fillMaxSize())
}
