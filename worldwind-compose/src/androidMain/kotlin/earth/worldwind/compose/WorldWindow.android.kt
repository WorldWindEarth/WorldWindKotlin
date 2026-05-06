package earth.worldwind.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import earth.worldwind.WorldWindow as PlatformWorldWindow

/**
 * Hosts the WorldWind engine inside the Compose tree on Android. Compose's surface stack is
 * Skia-backed and cannot host an EGL context directly, so the engine still draws into a
 * [android.view.SurfaceView]-derived control underneath; this Composable hides that detail
 * behind [WorldWindowState] and forwards activity `ON_RESUME`/`ON_PAUSE` to the underlying view
 * so the EGL context cycles correctly across activity lifecycle.
 */
@Composable
fun WorldWindow(
    state: WorldWindowState,
    modifier: Modifier = Modifier,
    onCreated: (PlatformWorldWindow) -> Unit = {},
) {
    val context = LocalContext.current
    val wwd = remember(state) {
        PlatformWorldWindow(context).also { window ->
            state.factory(window.engine)
            state.attach(window.engine, window)
            onCreated(window)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, wwd) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> wwd.onResume()
                Lifecycle.Event.ON_PAUSE -> wwd.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.detach()
        }
    }

    AndroidView(modifier = modifier, factory = { wwd })
}
