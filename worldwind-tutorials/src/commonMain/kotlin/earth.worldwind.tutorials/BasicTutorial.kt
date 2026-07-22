package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Basic globe demo with a continuously advancing day/night cycle. Mirrors the JVM-only
 * `DayNightCycleActivity` but cross-platform: instead of an Android `Choreographer` callback
 * the time advance lives in a tiny [AbstractRenderable] inside a dedicated [RenderableLayer].
 *
 * Diverges from other tutorials in two ways:
 *  - Sets [WorldWind.time] so the day/night terminator and star-field both run, instead of
 *    using the default [AbstractTutorial.start] which only sets the static
 *    `lightDirectionProvider`.
 *  - Adds an animator layer that, on each frame, ticks the engine time forward by
 *    [timeFactor] real seconds and requests a redraw.
 */
class BasicTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    /** Real-seconds-to-simulated-seconds factor. `3600` means 1 simulated hour per real second. */
    var timeFactor: Double = 3600.0

    private var lastFrameMs = 0L

    private val animator = object : AbstractRenderable("BasicTutorialTimeAnimator") {
        override fun doRender(rc: RenderContext) {
            val now = Clock.System.now().toEpochMilliseconds()
            val current = engine.time
            if (current != null && lastFrameMs > 0L) {
                val elapsedSec = (now - lastFrameMs) / 1000.0
                val advance = (elapsedSec * timeFactor).seconds
                engine.time = current.plus(advance)
                rc.requestRedraw()
            }
            lastFrameMs = now
        }
    }

    private val animatorLayer = RenderableLayer("BasicTutorialAnimator").apply {
        addRenderable(animator)
    }

    override fun start() {
        // Don't call super: we use the engine [time] for the terminator instead of the default
        // static [lightDirectionProvider]. Both pathways write [rc.lightDirection], so shadows
        // still work - they just track the moving sun rather than a fixed angle.
        engine.lightDirectionProvider = null
        engine.time = Clock.System.now()
        lastFrameMs = 0L
        engine.layers.addLayer(animatorLayer)
    }

    override fun stop() {
        engine.layers.removeLayer(animatorLayer)
        engine.time = null
    }
}
