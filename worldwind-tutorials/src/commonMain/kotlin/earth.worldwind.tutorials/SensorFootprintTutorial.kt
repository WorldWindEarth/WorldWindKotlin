package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableSensorFrameDepth
import earth.worldwind.draw.DrawableSensorFrames
import earth.worldwind.frame.FrameMetrics
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.image.ImageSource
import earth.worldwind.sensor.DemTileCache
import earth.worldwind.sensor.FootprintSolver
import earth.worldwind.sensor.Sensor
import earth.worldwind.sensor.SensorFrameOverlay
import earth.worldwind.sensor.ViewshedEngine
import earth.worldwind.shape.OrientationMode
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Placemark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Sensor frames: 100 drones. Drones fly loops over the ridges and valleys around Mount St. Helens,
 * 80 to 600 m above the ground, each with a camera whose frame a [SensorFrameOverlay] draws on
 * the terrain, entirely on the GPU, like sun shadows with the camera as the sun. Every drone flies
 * its own circle or figure-eight, at its own radius, speed and height, looks along its track and
 * slowly tilts its gimbal between 10° and 60° down. The animation runs on the frame clock.
 *
 * What is drawn can be switched while it runs (see the setters, or [actions] on platforms
 * without a panel):
 *  - the frustum frame: the frame's outline on the ground (off by default);
 *  - the seen tint: ground inside the frame the drone sees (on, red at 10 %);
 *  - the hidden tint: ground inside the frame behind a ridge (off, blue at 25 %);
 *  - the picture: an animated test card shared by all drones, shown on the ground each drone
 *    sees in place of the seen tint; the hidden tint stays;
 *  - the elevation outline: each drone's viewshed from a [ViewshedEngine] over the elevation
 *    data, its regions drawn as paths on the ground;
 *  - straight sides: with the frustum frame on, the frame drawn instead as four paths between
 *    the corner rays' landings from a [FootprintSolver], near and far edges on the ground, left
 *    and right sides straight lines through the air;
 *  - the drone count, 1, 10 or 100, and occlusion.
 *
 * The overlay's depth atlas holds [DrawableSensorFrameDepth.MAX_CELLS] frames: with 100 drones,
 * drones 1 to 16 are occluded and drones 17 to 100 are drawn without occlusion, so their seen
 * tint covers the whole frame and their hidden tint never shows.
 *
 * Render and draw time, the overlay's draw calls and the viewshed sweeps a second are shown in
 * [status] and written to the log every 5 s.
 */
class SensorFootprintTutorial(
    engine: WorldWind,
    /** Drones to start with: 1, 10 or 100. */
    droneCount: Int = MAX_DRONES,
) : AbstractTutorial(engine) {

    // ---- Settings, set from any thread, applied at the next frame ----

    @Volatile var isFrameShown = false
    @Volatile var isSeenShown = true
    @Volatile var seenColor = Color(1f, 0f, 0f, 1f)
    /** 0 to 1. */
    @Volatile var seenOpacity = 0.10f
    @Volatile var isHiddenShown = false
    @Volatile var hiddenColor = Color(0f, 0.3f, 1f, 1f)
    @Volatile var hiddenOpacity = 0.25f
    @Volatile var isPictureShown = false
    @Volatile var pictureOpacity = 0.8f
    @Volatile var isElevationOutlineShown = false
    @Volatile var isStraightSidesShown = false
    @Volatile var isOcclusionEnabled = true

    /** Drones flying now. */
    var droneCount = droneCount.closestCount()
        private set
    /** Count asked for by [setDroneCount], applied on the render thread at the next frame. */
    @Volatile private var pendingDroneCount = this.droneCount

    /** Flies 1, 10 or 100 drones (the nearest of those); safe to call from any thread. */
    fun setDroneCount(count: Int) {
        pendingDroneCount = count.closestCount()
        WorldWind.requestRedraw()
    }

    /** Frames the overlay can occlude; the rest are drawn without occlusion. */
    val atlasCells get() = DrawableSensorFrameDepth.MAX_CELLS

    /** What the panel shows, a few lines, refreshed twice a second. */
    @Volatile var status = ""
        private set

    // ---- Actions, for platforms that show buttons rather than a panel ----

    override val actions = arrayListOf(
        ACTION_FRAME, ACTION_SEEN, ACTION_SEEN_COLOR, ACTION_SEEN_OPACITY,
        ACTION_HIDDEN, ACTION_HIDDEN_COLOR, ACTION_HIDDEN_OPACITY,
        ACTION_PICTURE, ACTION_PICTURE_OPACITY, ACTION_ELEVATION_OUTLINE, ACTION_STRAIGHT_SIDES,
        ACTION_DRONES, ACTION_OCCLUSION,
    )

    override fun runAction(actionName: String) {
        when (actionName) {
            ACTION_FRAME -> isFrameShown = !isFrameShown
            ACTION_SEEN -> isSeenShown = !isSeenShown
            ACTION_SEEN_COLOR -> seenColor = nextColor(seenColor)
            ACTION_SEEN_OPACITY -> seenOpacity = nextOpacity(seenOpacity)
            ACTION_HIDDEN -> isHiddenShown = !isHiddenShown
            ACTION_HIDDEN_COLOR -> hiddenColor = nextColor(hiddenColor)
            ACTION_HIDDEN_OPACITY -> hiddenOpacity = nextOpacity(hiddenOpacity)
            ACTION_PICTURE -> isPictureShown = !isPictureShown
            ACTION_PICTURE_OPACITY -> pictureOpacity = nextOpacity(pictureOpacity)
            ACTION_ELEVATION_OUTLINE -> isElevationOutlineShown = !isElevationOutlineShown
            ACTION_STRAIGHT_SIDES -> isStraightSidesShown = !isStraightSidesShown
            ACTION_DRONES -> setDroneCount(DRONE_COUNTS[(DRONE_COUNTS.indexOf(pendingDroneCount) + 1) % DRONE_COUNTS.size])
            ACTION_OCCLUSION -> isOcclusionEnabled = !isOcclusionEnabled
        }
        WorldWind.requestRedraw()
    }

    // ---- The drones ----

    private class Drone(
        val index: Int,
        /** Centre of the loop. */
        val latitude: Double,
        val longitude: Double,
        val radius: Double,
        val isFigureEight: Boolean,
        /** +1 or -1: which way round. */
        val direction: Double,
        val heightAboveGround: Double,
        val speed: Double,
        var theta: Double,
        val tiltPeriod: Double,
        val tiltPhase: Double,
        val panAmplitude: Double,
        val panPeriod: Double,
        horizontalFov: Double,
        verticalFov: Double,
        range: Double,
        val color: Color,
    ) {
        val sensor = Sensor(
            position = Position.fromDegrees(latitude, longitude, 0.0),
            azimuth = 0.0.degrees, depression = 30.0.degrees,
            horizontalFov = horizontalFov.degrees, verticalFov = verticalFov.degrees, maxRange = range,
        )
        val placemark = Placemark(Position(sensor.position)).apply {
            altitudeMode = AltitudeMode.ABSOLUTE
            imageRotationReference = OrientationMode.RELATIVE_TO_GLOBE
            attributes.apply {
                imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
                imageOffset = Offset.center()
                imageScale = 1.2
                imageColor = Color(color)
                isDrawLeader = true
            }
        }
        private val metersPerDegreeLongitude = METERS_PER_DEGREE * cos(latitude * PI / 180)

        /** Flies [seconds] further along the loop at [time] seconds into the flight. */
        fun advance(seconds: Double, time: Double, globe: Globe) {
            // Constant ground speed: dθ = v dt / |dP/dθ|
            val (dx, dy) = tangent(theta)
            theta += direction * speed * seconds / sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3)
            val (x, y) = point(theta)
            val (tx, ty) = tangent(theta)
            val lat = latitude + y / METERS_PER_DEGREE
            val lon = longitude + x / metersPerDegreeLongitude
            val position = sensor.position
            position.latitude = lat.degrees
            position.longitude = lon.degrees
            position.altitude = globe.getElevation(position.latitude, position.longitude) + heightAboveGround
            val heading = atan2(direction * tx, direction * ty) * 180 / PI
            sensor.azimuth = (heading + panAmplitude * sin(2 * PI * time / panPeriod)).degrees.normalize360()
            // 10° to 60° down, slowly
            sensor.depression = (35.0 + 25.0 * sin(2 * PI * time / tiltPeriod + tiltPhase)).degrees
            placemark.position.copy(position)
            placemark.imageRotation = heading.degrees.normalize360()
        }

        /** East and north of the loop's centre, metres. */
        private fun point(t: Double) =
            if (isFigureEight) radius * sin(t) to radius * sin(t) * cos(t) else radius * cos(t) to radius * sin(t)

        private fun tangent(t: Double) =
            if (isFigureEight) radius * cos(t) to radius * cos(2 * t) else -radius * sin(t) to radius * cos(t)
    }

    private var drones = emptyList<Drone>()
    /** Bumped each time the drones are rebuilt, so the background work drops results for old ones. */
    private var droneGeneration = 0

    private val layer = RenderableLayer("Sensor frames").apply { isPickEnabled = false }
    private val placemarks = RenderableLayer("Drones").apply { isPickEnabled = false }
    private val outlineLayer = RenderableLayer("Elevation outlines").apply { isPickEnabled = false }
    private val sidesLayer = RenderableLayer("Straight sides").apply { isPickEnabled = false }
    private val overlay = SensorFrameOverlay().apply { outlineWidth = 2f }
    private val picture = TestPatternTexture()
    private val statusLines = List(STATUS_LINES) {
        ScreenText(screenOffset = Offset(OffsetMode.PIXELS, 12.0, OffsetMode.INSET_PIXELS, 12.0 + 22.0 * it))
    }
    private var running = false
    private var started = TimeSource.Monotonic.markNow()
    private var lastFrame = started

    /** Moves the drones once a frame, styles their frames, hands work to the background, measures. */
    private val animator = object : AbstractRenderable("Sensor frame animator") {
        override fun doRender(rc: RenderContext) {
            if (!running) return
            val now = TimeSource.Monotonic.markNow()
            if (pendingDroneCount != drones.size) rebuildDrones(pendingDroneCount)
            val seconds = (now - lastFrame).inWholeMicroseconds / 1e6
            val time = (now - started).inWholeMicroseconds / 1e6
            lastFrame = now
            for (drone in drones) drone.advance(seconds.coerceAtMost(0.25), time, rc.globe)
            styleFrames()
            handOff(rc.globe)
            applyStraightSides()
            applyElevationOutlines()
            measure(now)
            rc.requestRedraw() // keep the drones flying
        }
    }

    init {
        layer.addRenderable(animator) // first, so the frames below are drawn where it left the drones
        layer.addRenderable(overlay)
        for (line in statusLines) layer.addRenderable(line)
    }

    /**
     * The drones of [count]: the first at the crater's rim, the rest seeded at random over about
     * 25 × 25 km around the mountain, so a given count always flies the same drones.
     */
    private fun rebuildDrones(count: Int) {
        val random = Random(42)
        drones = List(count) { i ->
            val first = i == 0
            Drone(
                index = i,
                latitude = if (first) 46.205 else CENTER_LATITUDE + random.nextDouble(-0.11, 0.11),
                longitude = if (first) -122.185 else CENTER_LONGITUDE + random.nextDouble(-0.16, 0.16),
                radius = if (first) 900.0 else random.nextDouble(300.0, 1500.0),
                isFigureEight = if (first) true else random.nextBoolean(),
                direction = if (random.nextBoolean()) 1.0 else -1.0,
                heightAboveGround = if (first) 300.0 else random.nextDouble(80.0, 600.0),
                speed = random.nextDouble(10.0, 25.0),
                theta = random.nextDouble(0.0, 2 * PI),
                tiltPeriod = random.nextDouble(20.0, 40.0),
                tiltPhase = random.nextDouble(0.0, 2 * PI),
                panAmplitude = random.nextDouble(0.0, 25.0),
                panPeriod = random.nextDouble(12.0, 30.0),
                horizontalFov = random.nextDouble(30.0, 50.0),
                verticalFov = random.nextDouble(20.0, 35.0),
                range = random.nextDouble(2500.0, 4000.0),
                color = hueColor(i * 0.618034f),
            )
        }
        droneCount = count
        droneGeneration++
        overlay.frames.clear()
        placemarks.clearRenderables()
        for (drone in drones) {
            overlay.add(drone.sensor, Color(drone.color))
            placemarks.addRenderable(drone.placemark)
        }
        outlinePaths.forEach { it.clear() }
        outlinePaths = List(count) { mutableListOf() }
        outlineLayer.clearRenderables()
        appliedRings = null
        sidesLayer.clearRenderables()
        sidePaths = List(count) { i -> List(4) { side -> sidePath(drones[i].color, straight = side % 2 == 1) } }
        appliedCorners = null
        resetWindow(TimeSource.Monotonic.markNow())
    }

    /** Colours, picture and occlusion of every frame from the settings. */
    private fun styleFrames() {
        val frame = isFrameShown && !isStraightSidesShown
        val seen = seenColor
        val hidden = hiddenColor
        val seenAlpha = if (isSeenShown && !isPictureShown) seenOpacity else 0f
        val hiddenAlpha = if (isHiddenShown) hiddenOpacity else 0f
        overlay.isOcclusionEnabled = isOcclusionEnabled
        for ((i, f) in overlay.frames.withIndex()) {
            val color = drones[i].color
            f.outlineColor.set(color.red, color.green, color.blue, if (frame) 1f else 0f)
            // The picture replaces the seen tint on frames that carry it; the hidden tint stays
            f.fillColor.set(seen.red, seen.green, seen.blue, seenAlpha)
            f.occludedColor.set(hidden.red, hidden.green, hidden.blue, hiddenAlpha)
            f.media = if (isPictureShown) picture else null
            f.mediaOpacity = pictureOpacity
        }
    }

    // ---- Background work: viewsheds and corner rays, on the elevation tiles' thread ----

    /**
     * The tiles, the corner solver and the viewshed engine belong to [scope]'s thread, which on
     * Android is not the render thread. Each frame the render thread copies the drones' poses
     * and hands them over, at most one hand-off in flight; the results come back as whole new
     * arrays, read by the render thread when they change.
     */
    private val scope: CoroutineScope get() = engine.renderResourceCache.mainScope
    @Volatile private var handOffBusy = false
    private var background: Background? = null

    /** Results for the render thread, replaced whole. */
    private class Results<T>(val generation: Int, val values: Array<T?>)
    @Volatile private var cornerResults: Results<List<Position>>? = null
    @Volatile private var ringResults: Results<List<List<Position>>>? = null
    @Volatile private var sweepsPerSecond = 0
    @Volatile private var cornerSolveMillis = 0.0

    /** Lives on [scope]'s thread only. */
    private inner class Background(globe: Globe) {
        val tiles = DemTileCache(globe, scope, resolutionMeters = 30.0)
        val solver = FootprintSolver(tiles).apply { minDepth = 0; maxDepth = 0 } // the four corner rays
        val viewsheds = ViewshedEngine(tiles, scope)
        var generation = -1
        var sensors = emptyList<Sensor>()
        var inEngine = false

        fun run(generation: Int, poses: DoubleArray, fovs: DoubleArray, outline: Boolean, straight: Boolean) {
            val count = poses.size / POSE
            if (generation != this.generation) {
                viewsheds.clear()
                inEngine = false
                this.generation = generation
                sensors = List(count) { k ->
                    Sensor(Position(), 0.0.degrees, 0.0.degrees, fovs[3 * k].degrees, fovs[3 * k + 1].degrees, fovs[3 * k + 2])
                }
                cornerResults = null
                ringResults = null
            }
            for ((k, s) in sensors.withIndex()) {
                s.position.latitude = poses[POSE * k].degrees
                s.position.longitude = poses[POSE * k + 1].degrees
                s.position.altitude = poses[POSE * k + 2]
                s.azimuth = poses[POSE * k + 3].degrees
                s.depression = poses[POSE * k + 4].degrees
            }
            if (outline && !inEngine) {
                ringResults = Results(generation, arrayOfNulls(count))
                for ((k, s) in sensors.withIndex()) viewsheds.add(s) { viewshed ->
                    val current = ringResults
                    if (current != null && current.generation == generation) {
                        ringResults = Results(generation, current.values.copyOf().also { it[k] = viewshed.regions })
                    }
                }
                inEngine = true
            } else if (!outline && inEngine) {
                viewsheds.clear()
                inEngine = false
                ringResults = null
            }
            if (inEngine) {
                viewsheds.update()
                sweepsPerSecond = viewsheds.sweepsPerSecond
            } else {
                sweepsPerSecond = 0
                tiles.refreshChanged()
            }
            if (straight) {
                val start = TimeSource.Monotonic.markNow()
                val corners = arrayOfNulls<List<Position>>(count)
                for ((k, s) in sensors.withIndex()) corners[k] = solver.solve(s).corners
                cornerSolveMillis = start.elapsedNow().inWholeMicroseconds / 1000.0
                cornerResults = Results(generation, corners)
            } else cornerResults = null
        }
    }

    private fun handOff(globe: Globe) {
        val outline = isElevationOutlineShown
        val straight = isFrameShown && isStraightSidesShown
        if (handOffBusy || (!outline && !straight && background == null)) return
        val generation = droneGeneration
        val poses = DoubleArray(POSE * drones.size)
        val fovs = DoubleArray(3 * drones.size)
        for ((k, d) in drones.withIndex()) {
            val s = d.sensor
            poses[POSE * k] = s.position.latitude.inDegrees
            poses[POSE * k + 1] = s.position.longitude.inDegrees
            poses[POSE * k + 2] = s.position.altitude
            poses[POSE * k + 3] = s.azimuth.inDegrees
            poses[POSE * k + 4] = s.depression.inDegrees
            fovs[3 * k] = s.horizontalFov.inDegrees
            fovs[3 * k + 1] = s.verticalFov.inDegrees
            fovs[3 * k + 2] = s.maxRange
        }
        handOffBusy = true
        scope.launch {
            try {
                if (!running) return@launch
                val b = background ?: Background(globe).also { background = it }
                b.run(generation, poses, fovs, outline, straight)
            } finally {
                handOffBusy = false
            }
        }
    }

    // ---- Paths, on the render thread ----

    private var outlinePaths = emptyList<MutableList<Path>>()
    private var appliedRings: Results<List<List<Position>>>? = null
    private var sidePaths = emptyList<List<Path>>()
    private var appliedCorners: Results<List<Position>>? = null

    private fun groundPath(color: Color) = Path(emptyList()).apply {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
        isPickEnabled = false
        attributes.outlineColor = Color(color)
        attributes.outlineWidth = 2f
    }

    /** A side of the frame: near and far edges on the ground, left and right straight through the air. */
    private fun sidePath(color: Color, straight: Boolean) = if (straight) Path(emptyList()).apply {
        altitudeMode = AltitudeMode.ABSOLUTE
        pathType = PathType.LINEAR
        isPickEnabled = false
        attributes.outlineColor = Color(color)
        attributes.outlineWidth = 2f
    } else groundPath(color)

    private fun applyElevationOutlines() {
        val results = ringResults
        val shown = isElevationOutlineShown && results != null && results.generation == droneGeneration
        if (!shown) {
            if (appliedRings != null) { outlineLayer.clearRenderables(); outlinePaths.forEach { it.clear() }; appliedRings = null }
            return
        }
        if (results === appliedRings) return
        val previous = appliedRings
        for ((k, rings) in results!!.values.withIndex()) {
            if (previous != null && previous.values[k] === rings) continue
            val paths = outlinePaths[k]
            val wanted = rings ?: emptyList()
            while (paths.size < wanted.size) paths += groundPath(drones[k].color).also { outlineLayer.addRenderable(it) }
            for ((j, path) in paths.withIndex()) {
                val ring = wanted.getOrNull(j)
                path.positions = when {
                    ring == null || ring.size < 2 -> emptyList()
                    ring.first() == ring.last() -> ring
                    else -> ring + ring.first()
                }
                path.reset()
            }
        }
        appliedRings = results
    }

    private fun applyStraightSides() {
        val results = cornerResults
        val shown = isFrameShown && isStraightSidesShown && results != null && results.generation == droneGeneration
        if (!shown) {
            if (appliedCorners != null) { sidesLayer.clearRenderables(); appliedCorners = null }
            return
        }
        if (results === appliedCorners) return
        if (appliedCorners == null) for (paths in sidePaths) for (p in paths) sidesLayer.addRenderable(p)
        for ((k, corners) in results!!.values.withIndex()) {
            val c = corners ?: continue
            // Corners: top-left, top-right, bottom-right, bottom-left. Far edge, right side, near edge, left side
            for (side in 0 until 4) {
                val path = sidePaths[k][side]
                path.positions = listOf(c[side], c[(side + 1) % 4])
                path.reset()
            }
        }
        appliedCorners = results
    }

    // ---- Measurements ----

    /** Render and draw times of every frame, in microseconds, summed for a mean and deviation. */
    private class Metrics : FrameMetrics {
        private var renderStart = TimeSource.Monotonic.markNow()
        private var drawStart = TimeSource.Monotonic.markNow()
        var renders = 0L; var renderSum = 0.0; var renderSquares = 0.0
        @Volatile var draws = 0L; var drawSum = 0.0; var drawSquares = 0.0
        override fun beginRendering(rc: RenderContext) { renderStart = TimeSource.Monotonic.markNow() }
        override fun endRendering(rc: RenderContext) {
            val t = renderStart.elapsedNow().inWholeMicroseconds / 1000.0
            renderSum += t; renderSquares += t * t; renders++
        }
        override fun beginDrawing(dc: DrawContext) { drawStart = TimeSource.Monotonic.markNow() }
        override fun endDrawing(dc: DrawContext) {
            val t = drawStart.elapsedNow().inWholeMicroseconds / 1000.0
            drawSum += t; drawSquares += t * t; draws++
        }
        override fun reset() {
            renders = 0; renderSum = 0.0; renderSquares = 0.0
            draws = 0; drawSum = 0.0; drawSquares = 0.0
        }
    }

    private class Stats(
        val fps: Double, val renderMean: Double, val renderDeviation: Double, val drawMean: Double, val drawDeviation: Double,
        val overlayDraws: Double, val depthDraws: Double, val tiles: Double, val sweeps: Int,
    )

    private val metrics = Metrics()
    private var windowStart = TimeSource.Monotonic.markNow()
    private var lastStatus = windowStart
    private var windowFrames = 0

    private fun resetWindow(now: TimeSource.Monotonic.ValueTimeMark) {
        metrics.reset()
        DrawableSensorFrames.statTiles = 0
        DrawableSensorFrames.statDraws = 0
        DrawableSensorFrameDepth.statDraws = 0
        windowStart = now
        windowFrames = 0
    }

    private fun stats(now: TimeSource.Monotonic.ValueTimeMark): Stats {
        val seconds = (now - windowStart).inWholeMicroseconds / 1e6
        val renders = metrics.renders.coerceAtLeast(1)
        val draws = metrics.draws.coerceAtLeast(1)
        val frames = windowFrames.coerceAtLeast(1).toDouble()
        fun deviation(sum: Double, squares: Double, n: Long): Double {
            val mean = sum / n
            return sqrt((squares / n - mean * mean).coerceAtLeast(0.0))
        }
        return Stats(
            fps = windowFrames / seconds.coerceAtLeast(1e-3),
            renderMean = metrics.renderSum / renders, renderDeviation = deviation(metrics.renderSum, metrics.renderSquares, renders),
            drawMean = metrics.drawSum / draws, drawDeviation = deviation(metrics.drawSum, metrics.drawSquares, draws),
            overlayDraws = DrawableSensorFrames.statDraws / frames, depthDraws = DrawableSensorFrameDepth.statDraws / frames,
            tiles = DrawableSensorFrames.statTiles / frames, sweeps = sweepsPerSecond,
        )
    }

    private fun measure(now: TimeSource.Monotonic.ValueTimeMark) {
        windowFrames++
        if (now - lastStatus >= STATUS_INTERVAL) {
            lastStatus = now
            updateStatus(stats(now))
        }
        if (now - windowStart >= LOG_INTERVAL) {
            val s = stats(now)
            println("SensorFrames ${droneCount} drones [${optionsLabel()}]: ${line(s)}")
            resetWindow(now)
        }
    }

    private fun Double.f1() = ((this * 10).roundToInt() / 10.0).toString()

    private fun line(s: Stats) =
        "${s.fps.f1()} fps, render ${s.renderMean.f1()} ± ${s.renderDeviation.f1()} ms, " +
            "draw ${s.drawMean.f1()} ± ${s.drawDeviation.f1()} ms, overlay ${s.overlayDraws.f1()} draws " +
            "(${s.tiles.f1()} tiles) + ${s.depthDraws.f1()} depth draws a frame, ${s.sweeps} sweeps/s"

    private fun updateStatus(s: Stats) {
        val unoccluded = (droneCount - atlasCells).coerceAtLeast(0)
        val atlas = when {
            !isOcclusionEnabled -> "occlusion off"
            unoccluded == 0 -> "all $droneCount occluded"
            else -> "drones ${atlasCells + 1}–$droneCount unoccluded"
        }
        val lines = listOf(
            "$droneCount drones · ${s.fps.f1()} fps · atlas $atlasCells cells: $atlas",
            "render ${s.renderMean.f1()} ± ${s.renderDeviation.f1()} ms · draw ${s.drawMean.f1()} ± ${s.drawDeviation.f1()} ms",
            "overlay ${s.overlayDraws.f1()} + depth ${s.depthDraws.f1()} draws/frame · ${s.sweeps} sweeps/s" +
                if (isFrameShown && isStraightSidesShown) " · corners ${cornerSolveMillis.f1()} ms" else "",
            "[${optionsLabel()}]",
        )
        status = lines.joinToString("\n")
        if (isStatusOnGlobe) for ((i, text) in statusLines.withIndex()) text.text = lines.getOrElse(i) { "" }
    }

    /** Whether the status is drawn over the globe; off where the platform shows it in a panel. */
    var isStatusOnGlobe = true
        set(value) {
            field = value
            if (!value) for (text in statusLines) text.text = ""
        }

    private fun optionsLabel() = buildList {
        if (isFrameShown) add(if (isStraightSidesShown) "frame (straight sides)" else "frame")
        if (isSeenShown && !isPictureShown) add("seen ${(seenOpacity * 100).roundToInt()}%")
        if (isHiddenShown) add("hidden ${(hiddenOpacity * 100).roundToInt()}%")
        if (isPictureShown) add("picture ${(pictureOpacity * 100).roundToInt()}%")
        if (isElevationOutlineShown) add("elevation outline")
        if (isOcclusionEnabled) add("occlusion")
    }.joinToString(", ").ifEmpty { "nothing" }

    // ---- Lifecycle ----

    private fun lookAtDrones(count: Int) {
        engine.cameraFromLookAt(
            LookAt(
                position = Position(CENTER_LATITUDE.degrees, CENTER_LONGITUDE.degrees, 0.0), altitudeMode = AltitudeMode.ABSOLUTE,
                range = when { count <= 1 -> 1.2e4; count <= 10 -> 3.5e4; else -> 5.5e4 },
                heading = 0.0.degrees, tilt = 45.0.degrees, roll = 0.0.degrees
            )
        )
    }

    override fun start() {
        super.start()
        running = true
        engine.layers.addLayer(outlineLayer)
        engine.layers.addLayer(sidesLayer)
        engine.layers.addLayer(layer)
        engine.layers.addLayer(placemarks)
        engine.frameMetrics = metrics
        started = TimeSource.Monotonic.markNow()
        lastFrame = started
        resetWindow(started)
        lookAtDrones(pendingDroneCount)
        WorldWind.requestRedraw()
    }

    override fun stop() {
        super.stop()
        running = false
        engine.layers.removeLayer(outlineLayer)
        engine.layers.removeLayer(sidesLayer)
        engine.layers.removeLayer(layer)
        engine.layers.removeLayer(placemarks)
        if (engine.frameMetrics === metrics) engine.frameMetrics = null
        scope.launch { background?.viewsheds?.clear(); background = null }
    }

    companion object {
        const val ACTION_FRAME = "Frustum frame"
        const val ACTION_SEEN = "Seen tint"
        const val ACTION_SEEN_COLOR = "Seen colour"
        const val ACTION_SEEN_OPACITY = "Seen opacity"
        const val ACTION_HIDDEN = "Hidden tint"
        const val ACTION_HIDDEN_COLOR = "Hidden colour"
        const val ACTION_HIDDEN_OPACITY = "Hidden opacity"
        const val ACTION_PICTURE = "Picture"
        const val ACTION_PICTURE_OPACITY = "Picture opacity"
        const val ACTION_ELEVATION_OUTLINE = "Elevation outline"
        const val ACTION_STRAIGHT_SIDES = "Straight sides"
        const val ACTION_DRONES = "Drones 1/10/100"
        const val ACTION_OCCLUSION = "Occlusion"

        const val MAX_DRONES = 100
        /** The drone counts to choose from. */
        val DRONE_COUNTS = intArrayOf(1, 10, 100)

        /** The colours to choose from, by name. */
        val COLORS = listOf(
            "Red" to Color(1f, 0f, 0f, 1f), "Orange" to Color(1f, 0.55f, 0f, 1f), "Yellow" to Color(1f, 1f, 0f, 1f),
            "Green" to Color(0f, 0.9f, 0f, 1f), "Cyan" to Color(0f, 1f, 1f, 1f), "Blue" to Color(0f, 0.3f, 1f, 1f),
            "Magenta" to Color(1f, 0f, 1f, 1f), "White" to Color(1f, 1f, 1f, 1f),
        )
        /** Opacity steps the [ACTION_SEEN_OPACITY] style actions cycle through. */
        private val OPACITIES = floatArrayOf(0.10f, 0.25f, 0.50f, 0.75f, 1f)

        private const val CENTER_LATITUDE = 46.19
        private const val CENTER_LONGITUDE = -122.19
        private const val METERS_PER_DEGREE = 111_320.0
        private const val POSE = 5
        private const val STATUS_LINES = 4
        private val STATUS_INTERVAL = 500.milliseconds
        private val LOG_INTERVAL = 5.seconds

        private fun Int.closestCount() = DRONE_COUNTS.minBy { abs(it - this) }

        private fun nextColor(color: Color): Color {
            val i = COLORS.indexOfFirst { it.second == color }
            return Color(COLORS[(i + 1) % COLORS.size].second)
        }

        private fun nextOpacity(opacity: Float): Float {
            val i = OPACITIES.indexOfFirst { it > opacity + 1e-3f }
            return if (i < 0) OPACITIES[0] else OPACITIES[i]
        }

        /** Fully saturated colour of a hue in [0, 1). */
        private fun hueColor(hue: Float): Color {
            val h = (hue % 1f) * 6f
            val x = 1f - abs(h % 2f - 1f)
            return when (h.toInt()) {
                0 -> Color(1f, x, 0f, 1f); 1 -> Color(x, 1f, 0f, 1f); 2 -> Color(0f, 1f, x, 1f)
                3 -> Color(0f, x, 1f, 1f); 4 -> Color(x, 0f, 1f, 1f); else -> Color(1f, 0f, x, 1f)
            }
        }
    }
}
