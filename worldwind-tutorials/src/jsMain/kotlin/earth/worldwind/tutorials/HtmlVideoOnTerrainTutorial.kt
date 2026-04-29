package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.render.video.HtmlVideoTexture
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLVideoElement
import kotlin.js.Promise

/**
 * JS/browser tutorial: drapes a real STANAG 4609 drone clip onto the terrain via a hidden
 * `HTMLVideoElement` (which the browser decodes natively) and animates the projected footprint
 * with the embedded KLV telemetry.
 *
 * Video and timeline JSON are bundled cross-platform via moko-resources at
 * `MR.assets.video.drone_motion_*`. On JS, [dev.icerock.moko.resources.AssetResource.originalPath]
 * resolves to the webpack-rewritten URL of the bundled file (so cache-busted hashes etc. are
 * handled automatically); we feed that URL straight to the `<video>` element and to `fetch()`.
 */
class HtmlVideoOnTerrainTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val video: HTMLVideoElement = (document.createElement("video") as HTMLVideoElement).apply {
        src = MR.assets.video.drone_motion_mp4.originalPath
        // `playsInline` + `muted` lets the browser autoplay without a user-gesture prompt.
        // Crucially, autoplay *with audio* is blocked in Chrome/Safari without a click.
        muted = true
        autoplay = false
        loop = true
        // Hide from the page; we only want the decoded frames, not the visible element.
        style.display = "none"
        // Append so the element actually starts loading; some browsers stall decode for
        // detached media elements.
        document.body?.appendChild(this)
        load()
    }

    private val texture = HtmlVideoTexture(video, width = 1280, height = 720)

    private var inner: VideoOnTerrainTutorial? = null
    private var loaderScope: CoroutineScope? = null
    private var loaderJob: Job? = null

    override fun start() {
        super.start()
        // Fire and forget: fetch the JSON, parse, then build & start the inner tutorial.
        // Until that resolves the texture is still attached so the video element loads in
        // parallel with the JSON.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { loaderScope = it }
        loaderJob = scope.launch {
            val json = fetchText(MR.assets.video.drone_motion_json.originalPath) ?: return@launch
            val timeline = KlvTimeline.parse(json)
            // Build the inner tutorial only after the timeline is in hand — the corner
            // animation depends on it. The texture itself doesn't change.
            val tutorial = VideoOnTerrainTutorial(
                engine = engine,
                timeline = timeline,
                texture = texture,
                currentTimeMs = { (video.currentTime * 1000.0).toLong().coerceAtLeast(0L) },
                telemetryDelayMs = VideoOnTerrainTutorial.BUNDLED_DRONE_MOTION_DELAY_MS,
            )
            inner = tutorial
            tutorial.start()
            // Kick playback. Browsers reject `play()` from non-gesture contexts unless the
            // video is muted (which it is). The returned Promise<undefined> is dynamic on
            // the JS-binding side; ignore the result.
            try {
                video.play()
            } catch (_: Throwable) { /* autoplay blocked - user can hit the canvas to start */ }
            WorldWind.requestRedraw()
        }
    }

    override fun stop() {
        super.stop()
        loaderJob?.cancel()
        loaderJob = null
        loaderScope?.cancel()
        loaderScope = null
        inner?.stop()
        inner = null
        try { video.pause() } catch (_: Throwable) { /* idempotent */ }
    }

    // Surface the inner tutorial's UI actions to the JS tutorial host. The host reads
    // `actions` synchronously right after `start()`, but `inner` is built inside a
    // coroutine that may not have resolved yet, so we expose the static label list and
    // forward `runAction` calls - they no-op until `inner` is ready.
    //
    // ACTION_TOGGLE_3D is intentionally hidden on JS while the 3D-projection path
    // doesn't render correctly under WebGL (see docs/webgl-3d-projection-investigation.md
    // for the full investigation log and remaining options). The toggle still works at
    // the model level - re-add `VideoOnTerrainTutorial.ACTION_TOGGLE_3D` to this list
    // once the WebGL issue is fixed, no other changes needed.
    override val actions = arrayListOf<String>()

    override fun runAction(actionName: String) { inner?.runAction(actionName) }

    private suspend fun fetchText(url: String): String? = try {
        val response = window.fetch(url).await()
        (response.asDynamic().text() as Promise<String>).await()
    } catch (_: Throwable) {
        null
    }
}
