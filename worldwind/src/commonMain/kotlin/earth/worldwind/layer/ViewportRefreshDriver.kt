package earth.worldwind.layer

import earth.worldwind.WorldWind
import earth.worldwind.geom.Sector
import earth.worldwind.render.Renderable
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared machinery for layers that re-fetch their renderables for the current viewport during
 * render — used by [earth.worldwind.ogc.wfs.WfsLayer] (network) and [BulkFeatureLayer] (cache).
 * Encapsulates the subtle parts: padded-sector hysteresis, debounce, the optimistic-sector +
 * cancellation ordering, and the off-thread-build / render-thread-swap handoff.
 *
 * Threading: [onRender] runs on the render thread; `fetch` runs on [scope] and only writes the
 * volatile [pending] holder, which the render thread applies — the renderables list is never
 * mutated off-thread while being iterated.
 */
class ViewportRefreshDriver(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Debounce after the viewport settles before fetching, so a continuous pan/zoom collapses
     *  to one fetch instead of one per frame. */
    var debounceMillis = 400L
    /** Fraction the visible sector is padded by on each side — hysteresis so small pans stay
     *  inside the last fetched sector and skip a re-fetch. */
    var margin = 0.5

    /** Padded sector last requested; reset to `null` from the coroutine only on failure. */
    @Volatile private var fetchedSector: Sector? = null
    /** Renderables from the last successful fetch, awaiting a render-thread swap. */
    @Volatile private var pending: List<Renderable>? = null
    private var job: Job? = null

    /**
     * Render-thread entry point: apply any completed fetch via [apply], then — if [visible] has
     * left the last fetched (padded) sector — schedule a fresh debounced [fetch]. [apply] swaps
     * renderables on the render thread; [fetch] builds them on [scope].
     */
    fun onRender(visible: Sector, apply: (List<Renderable>) -> Unit, fetch: suspend (Sector) -> List<Renderable>) {
        pending?.let { p -> pending = null; apply(p) }
        if (visible.isEmpty) {
            // Terrain not tessellated yet (typical on the first frames). On an on-demand renderer
            // (Android RENDERMODE_WHEN_DIRTY) we must keep frames coming or the layer never starts.
            WorldWind.requestRedraw()
            return
        }
        if (fetchedSector?.contains(visible) == true) return // viewport still inside the last fetch
        val target = pad(visible)
        fetchedSector = target // optimistic — stops later frames re-scheduling during debounce
        job?.cancel()
        job = scope.launch {
            try {
                delay(debounceMillis)
                pending = fetch(target)
                WorldWind.requestRedraw() // wake a frame so onRender swaps the result in
            } catch (e: CancellationException) {
                throw e // superseded by a newer schedule (which already set its own fetchedSector)
            } catch (e: Throwable) {
                fetchedSector = null // failed → let the next frame retry
                logMessage(WARN, "ViewportRefreshDriver", "onRender", "viewport refresh failed", e)
            }
        }
    }

    /** Cancel any in-flight fetch and tear down the owned [scope]. */
    fun cancel() {
        job?.cancel()
        scope.cancel()
    }

    /** Visible extent grown by [margin] on every side, clamped to valid lat/lon. */
    private fun pad(visible: Sector): Sector {
        val padLat = visible.deltaLatitude.inDegrees * margin
        val padLon = visible.deltaLongitude.inDegrees * margin
        return Sector().setDegrees(
            visible.minLatitude.inDegrees - padLat,
            visible.minLongitude.inDegrees - padLon,
            visible.deltaLatitude.inDegrees + 2.0 * padLat,
            visible.deltaLongitude.inDegrees + 2.0 * padLon,
        )
    }
}
