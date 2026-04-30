package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.starfield.StarFieldLayer
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
 *  - Sets [AtmosphereLayer.time] (and [StarFieldLayer.time]) so the day/night terminator and
 *    star-field both run, instead of using the default [AbstractTutorial.start] which only
 *    sets the static `lightDirectionProvider`.
 *  - Adds an animator layer that, on each frame, ticks both layers' times forward by
 *    [timeFactor] real seconds and requests a redraw.
 */
class BasicTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    /** Real-seconds-to-simulated-seconds factor. `3600` means 1 simulated hour per real second. */
    var timeFactor: Double = 3600.0

    private var lastFrameMs = 0L

    private val animator = object : AbstractRenderable("BasicTutorialTimeAnimator") {
        override fun doRender(rc: RenderContext) {
            val atm = findAtmosphereLayer() ?: return
            val now = Clock.System.now().toEpochMilliseconds()
            val current = atm.time
            if (current != null && lastFrameMs > 0L) {
                val elapsedSec = (now - lastFrameMs) / 1000.0
                val advance = (elapsedSec * timeFactor).seconds
                val newTime = current.plus(advance)
                atm.time = newTime
                findStarFieldLayer()?.time = newTime
                rc.requestRedraw()
            }
            lastFrameMs = now
        }
    }

    private val animatorLayer = RenderableLayer("BasicTutorialAnimator").apply {
        addRenderable(animator)
    }

    override fun start() {
        // Don't call super: we use atmosphere [time] for the terminator instead of the default
        // static [lightDirectionProvider]. Both pathways write [rc.lightDirection], so shadows
        // still work - they just track the moving sun rather than a fixed angle.
        val atm = findAtmosphereLayer() ?: return
        atm.lightDirectionProvider = null
        val now = Clock.System.now()
        atm.time = now
        findStarFieldLayer()?.time = now
        lastFrameMs = 0L
        engine.layers.addLayer(animatorLayer)
    }

    override fun stop() {
        engine.layers.removeLayer(animatorLayer)
        findAtmosphereLayer()?.time = null
        findStarFieldLayer()?.time = null
    }

    private fun findStarFieldLayer(): StarFieldLayer? =
        engine.layers.firstOrNull { it is StarFieldLayer } as? StarFieldLayer
}
