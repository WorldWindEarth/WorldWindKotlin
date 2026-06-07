@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.render.video.AVPlayerVideoTexture
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.launch
import platform.AVFoundation.play
import platform.AVFoundation.pause
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.QuartzCore.CADisplayLink
import platform.darwin.NSObject
import earth.worldwind.util.Logger
import kotlinx.cinterop.ExportObjCClass

/**
 * iOS tutorial: drapes a STANAG 4609 drone clip onto the terrain via [AVPlayerVideoTexture],
 * driven by the embedded KLV telemetry. iOS analog of [HtmlVideoOnTerrainTutorial] and
 * [JvmVideoOnTerrainTutorial] — same outer flow (load video → load timeline → wrap in
 * [VideoOnTerrainTutorial]), different decoder.
 *
 * **Bundling.** Apps must include `drone_motion.mp4` and `drone_motion.json` in the main
 * `.app` bundle. moko-resources' iOS bundle packing is disabled on Windows hosts (see
 * `project_moko_resources_windows_ios` memory), so we resolve the files directly via
 * `NSBundle.mainBundle.pathForResource(...)`. If either file is missing the tutorial logs
 * a warning and remains a no-op so the rest of the app keeps running.
 *
 * **Redraw cadence.** AVFoundation decodes off the main thread but doesn't push frames into
 * our render loop on its own. We install a CADisplayLink that calls
 * [earth.worldwind.WorldWind.requestRedraw] every vsync while the video is playing — same
 * shape as [HtmlVideoTexture]'s `requestVideoFrameCallback` path.
 */
class AVPlayerVideoOnTerrainTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private var inner: VideoOnTerrainTutorial? = null
    private var texture: AVPlayerVideoTexture? = null
    private val displayLink = DisplayLinkRedraw()

    override fun start() {
        super.start()
        // Lazy resource lookup so a missing video file produces a warning, not a crash.
        val videoUrl = bundleURL("drone_motion", "mp4") ?: run {
            Logger.log(
                Logger.WARN,
                "drone_motion.mp4 not found in main bundle — video tutorial disabled"
            )
            return
        }
        val timelineJson = bundleText("drone_motion", "json") ?: run {
            Logger.log(
                Logger.WARN,
                "drone_motion.json not found in main bundle — video tutorial disabled"
            )
            return
        }

        val tex = AVPlayerVideoTexture.create(videoUrl, width = 1280, height = 720)
        texture = tex

        // Parse on the engine's main coroutine scope so we don't block the gesture path.
        engine.renderResourceCache.mainScope.launch {
            val timeline = KlvTimeline.parse(timelineJson)
            val tutorial = VideoOnTerrainTutorial(
                engine = engine,
                timeline = timeline,
                texture = tex,
                currentTimeMs = { tex.currentTimeMs() },
            )
            inner = tutorial
            tutorial.start()
            tex.player.play()
            // Pump WorldWind.requestRedraw at vsync while the video plays so frames keep
            // arriving on the engine's render loop.
            displayLink.attach { WorldWind.requestRedraw() }
        }
    }

    override fun stop() {
        super.stop()
        displayLink.detach()
        texture?.player?.pause()
        texture = null
        inner?.stop()
        inner = null
    }

    private fun bundleURL(name: String, ext: String): NSURL? =
        NSBundle.mainBundle.pathForResource(name, ext)?.let { NSURL(fileURLWithPath = it) }

    private fun bundleText(name: String, ext: String): String? {
        val path = NSBundle.mainBundle.pathForResource(name, ext) ?: return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return NSString.create(data, NSUTF8StringEncoding)?.toString()
    }
}

/**
 * Tiny wrapper around CADisplayLink that fires a Kotlin lambda at vsync. Used to drive
 * `WorldWind.requestRedraw` while video frames are arriving.
 *
 * Held as a separate `NSObject` because K/N forbids mixing Kotlin interfaces (lifecycle
 * scoping, mutable state) with Obj-C supertypes — same constraint that forced
 * `BasicWorldWindowController.DisplayLinkTarget` to be a separate class.
 */
@ExportObjCClass
private class DisplayLinkRedraw : NSObject() {
    private var link: CADisplayLink? = null
    private var tick: (() -> Unit)? = null

    fun attach(onTick: () -> Unit) {
        detach()
        tick = onTick
        val l = CADisplayLink.displayLinkWithTarget(this, NSSelectorFromString("fire"))
        l.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        link = l
    }

    fun detach() {
        link?.invalidate()
        link = null
        tick = null
    }

    @ObjCAction
    fun fire() { tick?.invoke() }
}
