package earth.worldwind.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import earth.worldwind.WorldWindow as PlatformWorldWindow

/**
 * Hosts the WorldWind engine inside Compose for Desktop. Skia and JOGL share no GL context, so
 * the engine's [com.jogamp.opengl.awt.GLJPanel] is wrapped in a [SwingPanel].
 */
@Composable
fun WorldWindow(
    state: WorldWindowState,
    modifier: Modifier = Modifier,
    onCreated: (PlatformWorldWindow) -> Unit = {},
) {
    val wwd = remember(state) {
        lateinit var window: PlatformWorldWindow
        // The factory runs on JOGL's init thread once the engine is constructed, so this is the
        // earliest point where we can hand the listener back to state.
        window = PlatformWorldWindow { engine ->
            state.factory(engine)
            state.attach(engine, window)
        }
        onCreated(window)
        window
    }

    DisposableEffect(wwd) {
        onDispose { state.detach() }
    }

    SwingPanel(modifier = modifier, factory = { wwd })
}
