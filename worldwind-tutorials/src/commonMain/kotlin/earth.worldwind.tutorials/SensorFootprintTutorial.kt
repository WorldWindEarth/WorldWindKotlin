package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableSensorFrameDepth
import earth.worldwind.draw.DrawableSensorFrames
import earth.worldwind.frame.FrameMetrics
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Location
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.image.ImageSource
import earth.worldwind.sensor.Sensor
import earth.worldwind.sensor.SensorFrameOverlay
import earth.worldwind.shape.OrientationMode
import earth.worldwind.shape.Placemark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Drones circle over Mount St. Helens, each with a framing sensor whose frame is drawn on the
 * ground as it moves: ten to start with, up to a thousand with the [ACTION_MORE] and
 * [ACTION_FEWER] actions. The frame rate and drone count are shown in the top left corner.
 *
 * The frames are drawn by a [SensorFrameOverlay], entirely on the GPU, like sun shadows with the
 * sensor as the sun: the terrain is drawn once more and every pixel along the edge of a sensor's
 * frame is tinted, so each frame's outline follows the hills exactly. Only the outline is drawn
 * here; the overlay can also fill the frame, and with occlusion on (a small depth map rendered from
 * each drone, like a shadow map) tint the ground the drone sees apart from ground behind a ridge.
 * The CPU only builds one matrix per drone, so every frame follows its drone every frame.
 *
 * Coordinates of a frame's corners, if an application needs them, come from casting its four
 * corner rays with a [earth.worldwind.sensor.FootprintSolver]; this tutorial only draws.
 */
class SensorFootprintTutorial(
    engine: WorldWind,
    /** Temporary: cycle through drawing set-ups and print what each costs. */
    private val measure: Boolean = false,
    /** Drones to start with, between [MIN_DRONES] and [MAX_DRONES]. */
    droneCount: Int = MIN_DRONES,
) : AbstractTutorial(engine) {

    private class Drone(
        val center: Location,
        val orbitRadius: Double,
        altitude: Double,
        speed: Double,
        var phase: Double,
        val depression: Double,
        val sweep: Double,
        val sweepPeriod: Double,
        horizontalFov: Double,
        verticalFov: Double,
        val color: Color,
    ) {
        val angularSpeed = speed / orbitRadius
        val sensor = Sensor(
            position = Position(center.latitude, center.longitude, altitude),
            azimuth = 0.0.degrees, depression = depression.degrees,
            horizontalFov = horizontalFov.degrees, verticalFov = verticalFov.degrees, maxRange = 10_000.0,
        )
        val placemark = Placemark(Position(sensor.position)).apply {
            altitudeMode = AltitudeMode.ABSOLUTE
            imageRotationReference = OrientationMode.RELATIVE_TO_GLOBE
            attributes.apply {
                imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
                imageOffset = Offset.center()
                imageScale = 1.5
                imageColor = Color(color)
                isDrawLeader = true
            }
        }
        private val location = Location()

        /** Flies [seconds] further round the orbit; the sensor looks in towards its centre, sweeping. */
        fun advance(seconds: Double, time: Double) {
            phase += angularSpeed * seconds
            center.greatCircleLocation(phase.radians, orbitRadius / EARTH_RADIUS, location)
            sensor.position.latitude = location.latitude
            sensor.position.longitude = location.longitude
            sensor.azimuth = (phase * 180 / PI + 180 + sweep * sin(2 * PI * time / sweepPeriod)).degrees.normalize360()
            placemark.position.copy(sensor.position)
            placemark.imageRotation = (phase * 180 / PI + 90).degrees.normalize360() // clockwise orbit
        }
    }

    override val actions = arrayListOf(ACTION_FEWER, ACTION_MORE)

    /** Drones flying now. */
    var droneCount = droneCount.coerceIn(MIN_DRONES, MAX_DRONES)
        private set
    private var drones = emptyList<Drone>()
    /** Count asked for by [setDroneCount], applied on the render thread at the next frame. */
    private var pendingDroneCount = this.droneCount

    private val layer = RenderableLayer("Sensor footprints").apply { isPickEnabled = false }
    private val placemarks = RenderableLayer("Drones").apply { isPickEnabled = false }
    private val frames = SensorFrameOverlay().apply {
        outlineWidth = 2f
        // Outline only: occlusion changes the fill and occluded tint alone, so it has nothing to show
        isOcclusionEnabled = false
    }
    private val fpsText = ScreenText()
    private var running = false
    private var started = TimeSource.Monotonic.markNow()
    private var lastFrame = started
    private var fpsWindowStart = started
    private var fpsFrames = 0

    /** Moves the drones once a frame and keeps the frame rate; the overlay draws the frames. */
    private val animator = object : AbstractRenderable("Sensor footprint animator") {
        override fun doRender(rc: RenderContext) {
            if (!running) return
            if (pendingDroneCount != drones.size) rebuildDrones(pendingDroneCount)
            val now = TimeSource.Monotonic.markNow()
            val seconds = (now - lastFrame).inWholeMicroseconds / 1e6
            val time = (now - started).inWholeMicroseconds / 1e6
            lastFrame = now
            for (drone in drones) drone.advance(seconds, time)
            updateFps(now)
            if (measure) measurePhase(now)
            rc.requestRedraw() // keep the drones flying
        }
    }

    init {
        layer.addRenderable(animator) // first, so the frames below are drawn where it left the drones
        layer.addRenderable(frames)
        layer.addRenderable(fpsText)
    }

    override fun runAction(actionName: String) {
        val step = DRONE_COUNTS.indexOfFirst { it >= pendingDroneCount }.coerceAtLeast(0)
        when (actionName) {
            ACTION_MORE -> setDroneCount(DRONE_COUNTS[(step + 1).coerceAtMost(DRONE_COUNTS.lastIndex)])
            ACTION_FEWER -> setDroneCount(DRONE_COUNTS[(step - 1).coerceAtLeast(0)])
        }
    }

    /** Flies [count] drones, between [MIN_DRONES] and [MAX_DRONES]; safe to call from any UI thread. */
    fun setDroneCount(count: Int) {
        pendingDroneCount = count.coerceIn(MIN_DRONES, MAX_DRONES)
        WorldWind.requestRedraw()
    }

    /**
     * The ten preset drones, then more at random over about 55 × 55 km around the mountain, seeded
     * so a given count always flies the same drones.
     */
    private fun rebuildDrones(count: Int) {
        val random = Random(42)
        drones = List(count) { i ->
            if (i < PRESETS.size) PRESETS[i].let {
                drone(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8], it[9], hueColor(i / PRESETS.size.toFloat()))
            } else drone(
                46.19 + random.nextDouble(-0.25, 0.25), -122.19 + random.nextDouble(-0.35, 0.35),
                radius = random.nextDouble(1200.0, 2800.0), altitude = random.nextDouble(2700.0, 3800.0),
                speed = random.nextDouble(20.0, 40.0), depression = random.nextDouble(30.0, 60.0),
                sweep = random.nextDouble(15.0, 40.0), sweepPeriod = random.nextDouble(10.0, 20.0),
                hFov = random.nextDouble(20.0, 45.0), vFov = random.nextDouble(15.0, 35.0),
                color = hueColor(random.nextFloat()),
            )
        }
        droneCount = count
        frames.frames.clear()
        placemarks.clearRenderables()
        for (drone in drones) {
            frames.add(drone.sensor, Color(drone.color)) // outline only: no fill, no occluded tint
            placemarks.addRenderable(drone.placemark)
        }
        fpsWindowStart = TimeSource.Monotonic.markNow()
        fpsFrames = 0
    }

    /** Frames counted over half a second at a time, shown with the drone count. */
    private fun updateFps(now: TimeSource.Monotonic.ValueTimeMark) {
        fpsFrames++
        val window = now - fpsWindowStart
        if (window >= FPS_WINDOW) {
            val fps = fpsFrames / (window.inWholeMicroseconds / 1e6)
            fpsText.text = "${(fps * 10).roundToInt() / 10.0} fps · $droneCount drones"
            fpsWindowStart = now
            fpsFrames = 0
        } else if (fpsText.text.isEmpty()) fpsText.text = "… fps · $droneCount drones"
    }

    // ---- Temporary measurement mode ----

    /** Frames rendered, to turn into frames per second over each phase. */
    private class Recorder : FrameMetrics {
        var renders = 0
        override fun beginRendering(rc: RenderContext) {}
        override fun endRendering(rc: RenderContext) { renders++ }
        override fun beginDrawing(dc: DrawContext) {}
        override fun endDrawing(dc: DrawContext) {}
        override fun reset() { renders = 0 }
    }

    private class Phase(val name: String, val frames: Boolean, val occlusion: Boolean)

    private val phases = listOf(
        Phase("warm-up", frames = true, occlusion = true),
        Phase("A map + drone icons, no frames", frames = false, occlusion = false),
        Phase("B frames, no occlusion", frames = true, occlusion = false),
        Phase("C frames + occlusion", frames = true, occlusion = true),
    )
    private val recorder = Recorder()
    private var phaseIndex = 0
    private var phaseStart = TimeSource.Monotonic.markNow()
    private var currentPhase = phases[0]

    private fun measurePhase(now: TimeSource.Monotonic.ValueTimeMark) {
        if (now - phaseStart < PHASE_LENGTH) return
        report(now)
        phaseIndex++
        if (phaseIndex >= phases.size) {
            println("MEASURE ---- cycle done ----")
            phaseIndex = 1 // the warm-up runs once
        }
        enter(phases[phaseIndex])
        phaseStart = now
    }

    private fun enter(phase: Phase) {
        currentPhase = phase
        frames.isEnabled = phase.frames
        frames.isOcclusionEnabled = phase.occlusion
        recorder.reset()
        DrawableSensorFrames.statTiles = 0
        DrawableSensorFrames.statDraws = 0
        DrawableSensorFrameDepth.statDraws = 0
    }

    private fun report(now: TimeSource.Monotonic.ValueTimeMark) {
        val seconds = (now - phaseStart).inWholeMicroseconds / 1e6
        val frames = maxOf(recorder.renders, 1)
        val fps = recorder.renders / seconds
        println("MEASURE ${currentPhase.name} ($droneCount drones): ${(fps * 10).roundToInt() / 10.0} fps, " +
            "${(1000 / fps * 10).roundToInt() / 10.0} ms a frame; " +
            "${DrawableSensorFrames.statTiles / frames} tiles, ${DrawableSensorFrames.statDraws / frames} overlay draws, " +
            "${DrawableSensorFrameDepth.statDraws / frames} depth draws a frame")
    }

    override fun start() {
        super.start()
        running = true
        engine.layers.addLayer(layer)
        engine.layers.addLayer(placemarks)
        if (measure) {
            engine.frameMetrics = recorder
            phaseIndex = 0
            enter(phases[0])
            phaseStart = TimeSource.Monotonic.markNow()
        }
        started = TimeSource.Monotonic.markNow()
        lastFrame = started
        fpsWindowStart = started
        engine.cameraFromLookAt(
            LookAt(
                position = Position(46.195.degrees, (-122.190).degrees, 0.0), altitudeMode = AltitudeMode.ABSOLUTE,
                range = if (droneCount > MIN_DRONES) 7e4 else 3.2e4, heading = 0.0.degrees, tilt = 50.0.degrees, roll = 0.0.degrees
            )
        )
        WorldWind.requestRedraw()
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
        engine.layers.removeLayer(placemarks)
        if (engine.frameMetrics === recorder) engine.frameMetrics = null
        running = false
    }

    companion object {
        const val ACTION_FEWER = "Fewer drones"
        const val ACTION_MORE = "More drones"
        const val MIN_DRONES = 10
        const val MAX_DRONES = 1000
        /** The counts [ACTION_FEWER] and [ACTION_MORE] step through. */
        val DRONE_COUNTS = intArrayOf(10, 20, 50, 100, 200, 500, 1000)

        private const val EARTH_RADIUS = 6_371_000.0
        private val PHASE_LENGTH = 10.seconds
        private val FPS_WINDOW = 500.milliseconds

        /**
         * The ten drones a session starts with: centre latitude, longitude, orbit radius (m),
         * altitude above the ellipsoid (m), speed (m/s), depression (°), sweep (°), sweep period (s),
         * horizontal and vertical FOV (°).
         */
        private val PRESETS = listOf(
            doubleArrayOf(46.200, -122.190, 2500.0, 3600.0, 35.0, 50.0, 25.0, 14.0, 40.0, 30.0),
            doubleArrayOf(46.235, -122.150, 2000.0, 3200.0, 30.0, 40.0, 30.0, 18.0, 30.0, 20.0),
            doubleArrayOf(46.165, -122.235, 2200.0, 3000.0, 28.0, 35.0, 20.0, 11.0, 25.0, 18.0),
            doubleArrayOf(46.150, -122.140, 1800.0, 2800.0, 25.0, 45.0, 35.0, 16.0, 35.0, 25.0),
            doubleArrayOf(46.245, -122.225, 2600.0, 3400.0, 40.0, 30.0, 15.0, 20.0, 20.0, 15.0),
            doubleArrayOf(46.190, -122.105, 2300.0, 3100.0, 32.0, 38.0, 25.0, 13.0, 30.0, 22.0),
            doubleArrayOf(46.125, -122.195, 2000.0, 2700.0, 27.0, 33.0, 30.0, 17.0, 28.0, 20.0),
            doubleArrayOf(46.215, -122.270, 2400.0, 3300.0, 36.0, 42.0, 20.0, 15.0, 45.0, 30.0),
            doubleArrayOf(46.265, -122.180, 2100.0, 3500.0, 30.0, 55.0, 30.0, 12.0, 35.0, 35.0),
            doubleArrayOf(46.175, -122.160, 1500.0, 3000.0, 22.0, 60.0, 40.0, 10.0, 30.0, 20.0),
        )

        /** Fully saturated colour of a hue in [0, 1). */
        private fun hueColor(hue: Float): Color {
            val h = (hue % 1f) * 6f
            val x = 1f - abs(h % 2f - 1f)
            return when (h.toInt()) {
                0 -> Color(1f, x, 0f, 1f); 1 -> Color(x, 1f, 0f, 1f); 2 -> Color(0f, 1f, x, 1f)
                3 -> Color(0f, x, 1f, 1f); 4 -> Color(x, 0f, 1f, 1f); else -> Color(1f, 0f, x, 1f)
            }
        }

        private fun drone(
            lat: Double, lon: Double, radius: Double, altitude: Double, speed: Double, depression: Double,
            sweep: Double, sweepPeriod: Double, hFov: Double, vFov: Double, color: Color,
        ) = Drone(
            Location(lat.degrees, lon.degrees), radius, altitude, speed,
            phase = (lat * 1000 + lon * 100) % (2 * PI), depression = depression, sweep = sweep,
            sweepPeriod = sweepPeriod, horizontalFov = hFov, verticalFov = vFov, color = color,
        )
    }
}
