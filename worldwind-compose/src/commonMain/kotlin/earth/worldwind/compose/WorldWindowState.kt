package earth.worldwind.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.worldwind.WorldWind

/**
 * Holds engine state for a [WorldWindow] Composable so app code can configure layers, camera and
 * interactions without depending on the platform-specific surface that hosts the engine.
 *
 * [requestRedraw] is safe to call from any thread — the host listener marshals to its UI thread.
 */
@Stable
class WorldWindowState internal constructor(
    internal val factory: (WorldWind) -> Unit,
) {
    private var _engine by mutableStateOf<WorldWind?>(null)
    private var listener: WorldWind.EventListener? = null

    val engine: WorldWind? get() = _engine

    internal fun attach(engine: WorldWind, listener: WorldWind.EventListener) {
        _engine = engine
        this.listener = listener
    }

    internal fun detach() {
        _engine = null
        listener = null
    }

    fun requestRedraw() { listener?.requestRedraw() }
}

@Composable
fun rememberWorldWindowState(factory: (WorldWind) -> Unit = {}): WorldWindowState =
    remember { WorldWindowState(factory) }
