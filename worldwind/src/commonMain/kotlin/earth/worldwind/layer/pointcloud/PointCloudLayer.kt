package earth.worldwind.layer.pointcloud

import earth.worldwind.WorldWind
import earth.worldwind.formats.las.LasCrs
import earth.worldwind.formats.las.LasPointCloud
import earth.worldwind.formats.las.LasReader
import earth.worldwind.formats.las.RenderYield
import earth.worldwind.formats.ogc3d.PntsPayload
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.coords.UTMCoordConverter
import earth.worldwind.globe.Globe
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.layer.ogc3d.content.PointCloudContent
import earth.worldwind.layer.ogc3d.content.preparePointCloudContent
import earth.worldwind.layer.ogc3d.content.syncPointCloudContentGpu
import earth.worldwind.layer.ogc3d.draw.DrawableTilePoints
import earth.worldwind.layer.ogc3d.program.Ogc3dTilesPointsProgram
import earth.worldwind.layer.ogc3d.stream.HttpTileByteSource
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.math.tan

/**
 * Renders a standalone LAS/LAZ point cloud on the globe. Loading and parsing happen off the
 * render thread; the first frame captures the [Globe] and georeferences the cloud into
 * GPU-resident chunks, then every frame enqueues them through the engine's existing point path
 * ([DrawableTilePoints] + [Ogc3dTilesPointsProgram] + [PointCloudContent]) — the same
 * size-attenuated round splats the 3D-Tiles `.pnts` reader draws.
 *
 * Georeferencing: geographic and WGS84/UTM coordinate systems are detected from the file's
 * GeoKey VLR and converted automatically. For any other CRS, set [projection] to map native
 * `(x, y, z)` to a globe [Position]; otherwise the coordinates are assumed geographic and a
 * warning is logged.
 *
 * Compressed `.laz` files need a `LazChunkDecoder` installed via
 * `earth.worldwind.formats.las.LasDecoderRegistry` — [loadLas] fails clearly if it's missing.
 */
open class PointCloudLayer(displayName: String? = "Point Cloud") : AbstractLayer(displayName) {

    /** On-screen splat diameter in pixels at 1 m eye depth; attenuates with distance. */
    var pointSize = 3f

    /** Cascade-shadow participation. Standalone clouds default to none. */
    var shadowMode = ShadowMode.DISABLED

    /** Hard cap on rendered points; the cloud is uniformly stride-decimated above it. */
    var maxPoints = 5_000_000

    /** Optional native-CRS → globe [Position] mapping. When set it OVERRIDES the file's
     *  detected CRS, so it can reproject a coordinate system the engine doesn't know or
     *  re-place/re-base the cloud (e.g. drop it onto a flat globe). Applied per point. */
    var projection: ((x: Double, y: Double, z: Double) -> Position)? = null

    /** How the cloud's Z is interpreted. [AltitudeMode.ABOVE_SEA_LEVEL] (default) treats it as
     *  orthometric height — how nearly all LiDAR ships — and adds the geoid offset so it shares
     *  the globe's datum and rests on terrain (terrain is `elevation + geoid.getOffset`; a raw
     *  orthometric Z would float by the geoid separation). [AltitudeMode.ABSOLUTE] treats Z as
     *  already-ellipsoidal. Ground-relative modes don't apply — a cloud's coordinates are absolute. */
    var altitudeMode = AltitudeMode.ABOVE_SEA_LEVEL

    /** Metres to raise (or lower) the whole cloud, on top of [altitudeMode]. Applied live at draw
     *  time along the local up axis, so a change takes effect on the next redraw (the caller
     *  triggers it) with no reload. Nudge up to clear coarse terrain, correct a residual datum
     *  mismatch, or lift clear of the surface. */
    var altitudeOffset = 0.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadSeq = seq++

    /** Parsed but not-yet-georeferenced cloud, set by [loadLas]. */
    @Volatile private var pendingCloud: LasPointCloud? = null
    /** Georeferenced, GPU-ready chunks, set once [buildChunks] finishes. */
    @Volatile private var chunks: List<PointCloudChunk>? = null
    @Volatile private var building = false
    /** True once every chunk is built; while false, render keeps requesting redraws (progressive). */
    @Volatile private var buildComplete = false
    /** Geographic footprint of the cloud; published as a ground-coverage region so terrain
     *  stencil-skips the cloud's pixels. Computed by [buildChunks]. */
    @Volatile private var coverageSector: Sector? = null
    /** Unit up axis at the cloud origin; [altitudeOffset] is applied along it at draw time. */
    @Volatile private var originUp: Vec3? = null

    /** Loads a LAS/LAZ file from raw bytes. Suspends on the parse; the result renders on a
     *  subsequent frame once the globe is available for georeferencing. */
    suspend fun loadLas(bytes: ByteArray) {
        val cloud = LasReader.parse(bytes)
        chunks = null
        coverageSector = null
        originUp = null
        building = false
        buildComplete = false
        pendingCloud = cloud
        WorldWind.requestRedraw()
    }

    /** Loads a LAS/LAZ file from an http(s) URL. */
    suspend fun loadLas(url: String) {
        val source = HttpTileByteSource()
        try {
            val response = source.get(url)
            require(response.statusCode in 200..299) {
                "failed to fetch $url: HTTP ${response.statusCode} ${response.statusMessage}"
            }
            loadLas(response.bytes)
        } finally {
            source.close()
        }
    }

    /** Frees the coroutine scope. Call when the layer is permanently discarded. */
    fun dispose() {
        scope.cancel()
        pendingCloud = null
        chunks = null
        coverageSector = null
        originUp = null
    }

    override fun doRender(rc: RenderContext) {
        chunks?.let { ready ->
            // Publish the cloud footprint so terrain stencil-skips GROUND_COVERED_BIT pixels the
            // points write — otherwise coarse terrain occludes on-ground points (DEM error).
            coverageSector?.let {
                rc.groundCoverageRegions.add(it)
                rc.hasGroundCoverageMask = true
            }
            for (chunk in ready) enqueueChunk(rc, chunk)
            if (!buildComplete) rc.requestRedraw() // keep animating while chunks stream in
            return
        }
        val cloud = pendingCloud ?: return
        if (!building) {
            building = true
            val globe = rc.globe
            scope.launch {
                buildChunks(cloud, globe) // publishes chunks progressively
                buildComplete = true
                pendingCloud = null
                WorldWind.requestRedraw()
            }
        }
        rc.requestRedraw() // keep animating until the async build lands
    }

    /** Off-thread: georeference each point relative to a shared origin (float precision at
     *  globe scale, the same RTC trick the `.pnts` path uses) and pack into ≤[CHUNK_POINTS]
     *  [PointCloudContent] chunks, applying stride decimation past [maxPoints]. Publishes each
     *  chunk as it completes (progressive display) and yields to the event loop between batches
     *  so single-threaded targets (JS/wasm) stay responsive during the build. */
    private suspend fun buildChunks(cloud: LasPointCloud, globe: Globe) {
        val total = cloud.pointCount
        val stride = if (total > maxPoints) ceil(total.toDouble() / maxPoints).toInt() else 1
        val kept = (total + stride - 1) / stride
        if (stride > 1) logMessage(
            Logger.INFO, "PointCloudLayer", "buildChunks",
            "decimating $total points → $kept (stride $stride) to stay under maxPoints=$maxPoints",
        )

        val converter = UTMCoordConverter()
        val scratch = Vec3()
        // Geoid offset (once, at the centroid) so orthometric heights rest on terrain.
        val geoidOffset = centroidGeoidOffset(cloud, globe, converter)
        // Geographic footprint (from the native bbox corners) for the ground-coverage stencil.
        coverageSector = computeCoverageSector(cloud, converter)
        // Origin at the cloud centroid so per-point float offsets stay small.
        val cx = (cloud.minX + cloud.maxX) * 0.5
        val cy = (cloud.minY + cloud.maxY) * 0.5
        val origin = Vec3()
        toCartesian(cloud, cx, cy, (cloud.minZ + cloud.maxZ) * 0.5, globe, converter, geoidOffset, origin)
        // Up axis at the origin for the live [altitudeOffset] (applied at draw time, not baked).
        val centroid = Location().also { nativeToLatLon(cloud, cx, cy, (cloud.minZ + cloud.maxZ) * 0.5, converter, it) }
        originUp = globe.geographicToCartesianNormal(centroid.latitude, centroid.longitude, Vec3())

        // Switch doRender into render mode (sector/origin ready); chunks now stream in below.
        chunks = emptyList()

        var chunkPositions = FloatArray(minOf(kept, CHUNK_POINTS) * 3)
        var chunkColors = ByteArray(minOf(kept, CHUNK_POINTS) * 4)
        var fill = 0
        var chunkIndex = 0

        fun flush() {
            if (fill == 0) return
            val positions = if (fill * 3 == chunkPositions.size) chunkPositions else chunkPositions.copyOf(fill * 3)
            val colors = if (fill * 4 == chunkColors.size) chunkColors else chunkColors.copyOf(fill * 4)
            val content = PointCloudContent(rtcCenter = null)
            content.preparePointCloudContent(
                PntsPayload(pointsLength = fill, positions = positions, colors = colors, rtcCenter = null),
                contentUri = "pointcloud:$loadSeq:$chunkIndex",
            )
            // Publish the finished chunk (new immutable snapshot) and repaint to show it.
            chunks = (chunks ?: emptyList()) + PointCloudChunk(content, Vec3(origin.x, origin.y, origin.z))
            WorldWind.requestRedraw()
            chunkIndex++
            fill = 0
        }

        val yielder = RenderYield()
        var processed = 0
        var i = 0
        while (i < total) {
            if (processed++ and 0xFF == 0) yielder.tick() // yield when the frame budget is spent
            toCartesian(cloud, cloud.positions[i * 3], cloud.positions[i * 3 + 1], cloud.positions[i * 3 + 2],
                globe, converter, geoidOffset, scratch)
            val p = fill * 3
            chunkPositions[p] = (scratch.x - origin.x).toFloat()
            chunkPositions[p + 1] = (scratch.y - origin.y).toFloat()
            chunkPositions[p + 2] = (scratch.z - origin.z).toFloat()
            val c = fill * 4
            chunkColors[c] = cloud.colors[i * 4]
            chunkColors[c + 1] = cloud.colors[i * 4 + 1]
            chunkColors[c + 2] = cloud.colors[i * 4 + 2]
            chunkColors[c + 3] = cloud.colors[i * 4 + 3]
            fill++
            if (fill == CHUNK_POINTS) {
                flush()
                val remaining = ((total - i - stride + stride - 1) / stride).coerceAtLeast(0)
                val next = minOf(remaining, CHUNK_POINTS).coerceAtLeast(1)
                chunkPositions = FloatArray(next * 3)
                chunkColors = ByteArray(next * 4)
            }
            i += stride
        }
        flush()
        if (chunks == null) chunks = emptyList() // empty cloud: still mark as built
    }

    /** Native CRS `(x, y, z)` → globe ECEF into [result]. An explicit [projection] takes
     *  precedence over the file's detected CRS, so callers can override placement (e.g. drop
     *  the cloud onto a flat globe, or reproject a CRS the engine doesn't know). [geoidOffset]
     *  (computed once per cloud, see [centroidGeoidOffset]) is added so the cloud shares the
     *  globe's vertical datum and rests on terrain — the engine renders terrain the same way. */
    private fun toCartesian(
        cloud: LasPointCloud, x: Double, y: Double, z: Double,
        globe: Globe, converter: UTMCoordConverter, geoidOffset: Double, result: Vec3,
    ) {
        val lat: Angle
        val lon: Angle
        val alt: Double
        val proj = projection
        if (proj != null) {
            val p = proj(x, y, z)
            lat = p.latitude; lon = p.longitude; alt = p.altitude
        } else when (val crs = cloud.crs) {
            is LasCrs.Utm -> {
                converter.convertUTMToGeodetic(crs.zone, crs.hemisphere, x, y)
                lat = converter.latitude.radians; lon = converter.longitude.radians; alt = z
            }
            is LasCrs.Geographic -> { lat = y.degrees; lon = x.degrees; alt = z }
            else -> { warnUnknownCrs(); lat = y.degrees; lon = x.degrees; alt = z }
        }
        globe.geographicToCartesian(lat, lon, alt + geoidOffset, result)
    }

    /** Geoid (EGM96) offset at the cloud centroid, applied uniformly to every point so
     *  orthometric LAS heights share the globe's ellipsoidal datum. One value keeps the cloud
     *  internally consistent even while the geoid grid is still loading, and it varies by < 1 cm
     *  across a cloud this size. Zero for [AltitudeMode.ABSOLUTE] (already-ellipsoidal Z). */
    private fun centroidGeoidOffset(cloud: LasPointCloud, globe: Globe, converter: UTMCoordConverter): Double {
        if (altitudeMode == AltitudeMode.ABSOLUTE) return 0.0
        val cx = (cloud.minX + cloud.maxX) * 0.5
        val cy = (cloud.minY + cloud.maxY) * 0.5
        val lat: Angle
        val lon: Angle
        val proj = projection
        if (proj != null) {
            val p = proj(cx, cy, (cloud.minZ + cloud.maxZ) * 0.5)
            lat = p.latitude; lon = p.longitude
        } else when (val crs = cloud.crs) {
            is LasCrs.Utm -> {
                converter.convertUTMToGeodetic(crs.zone, crs.hemisphere, cx, cy)
                lat = converter.latitude.radians; lon = converter.longitude.radians
            }
            else -> { lat = cy.degrees; lon = cx.degrees }
        }
        return globe.geoid.getOffset(lat, lon).toDouble()
    }

    /** Geographic bounding [Sector] of the cloud, unioned from the native bounding-box corners
     *  (plus the centroid). Fine for the compact footprints point clouds cover. */
    private fun computeCoverageSector(cloud: LasPointCloud, converter: UTMCoordConverter): Sector {
        val sector = Sector().setEmpty()
        val zc = (cloud.minZ + cloud.maxZ) * 0.5
        val loc = Location()
        val xs = doubleArrayOf(cloud.minX, cloud.maxX, (cloud.minX + cloud.maxX) * 0.5)
        val ys = doubleArrayOf(cloud.minY, cloud.maxY, (cloud.minY + cloud.maxY) * 0.5)
        for (px in xs) for (py in ys) {
            nativeToLatLon(cloud, px, py, zc, converter, loc)
            sector.union(loc)
        }
        return sector
    }

    /** Native `(x, y)` → geographic [Location] using the same projection/CRS logic as
     *  [toCartesian] (geoid-independent — only lat/lon are needed). */
    private fun nativeToLatLon(
        cloud: LasPointCloud, x: Double, y: Double, z: Double, converter: UTMCoordConverter, result: Location,
    ) {
        val proj = projection
        if (proj != null) {
            val p = proj(x, y, z)
            result.set(p.latitude, p.longitude)
        } else when (val crs = cloud.crs) {
            is LasCrs.Utm -> {
                converter.convertUTMToGeodetic(crs.zone, crs.hemisphere, x, y)
                result.set(converter.latitude.radians, converter.longitude.radians)
            }
            else -> result.set(y.degrees, x.degrees)
        }
    }

    private var warnedUnknownCrs = false
    private fun warnUnknownCrs() {
        if (warnedUnknownCrs) return
        warnedUnknownCrs = true
        logMessage(
            Logger.WARN, "PointCloudLayer", "toCartesian",
            "unrecognised LAS CRS and no projection set — assuming geographic (x=lon, y=lat)",
        )
    }

    private val originTransform = Matrix4()
    private val scratchWorldCenter = Vec3()

    private fun enqueueChunk(rc: RenderContext, chunk: PointCloudChunk) {
        val content = chunk.content
        content.syncPointCloudContentGpu(rc)
        if (content.pointCount <= 0) return
        val vbo = rc.renderResourceCache[content.vboKey ?: return] as? BufferObject ?: return

        val pool = rc.getDrawablePool(DrawableTilePoints.KEY)
        val drawable = DrawableTilePoints.obtain(pool)
        drawable.content = content
        drawable.vertexBuffer = vbo
        drawable.program = Ogc3dTilesPointsProgram.get(rc)
        drawable.shadowMode = shadowMode
        drawable.isFallback = false
        drawable.stencilId = 0
        drawable.basePointSize = pointSize

        val fovRad = rc.camera.fieldOfView.inRadians
        val focal = (rc.viewport.height / (2.0 * tan(fovRad * 0.5))).toFloat()
        drawable.focalLengthPixels = if (focal.isFinite() && focal > 0f) focal else 1f

        // Live altitude offset along the local up axis — nudges the whole cloud without a rebuild.
        val up = originUp
        val ox = chunk.origin.x + if (up != null) up.x * altitudeOffset else 0.0
        val oy = chunk.origin.y + if (up != null) up.y * altitudeOffset else 0.0
        val oz = chunk.origin.z + if (up != null) up.z * altitudeOffset else 0.0
        drawable.tileToWorld.copy(originTransform.setToTranslation(ox, oy, oz))

        val sphere = content.localBoundingSphere
        scratchWorldCenter.set(ox + sphere.center.x, oy + sphere.center.y, oz + sphere.center.z)
        drawable.setWorldBounds(scratchWorldCenter, sphere.radius)

        rc.offerSurfaceDrawable(drawable, Double.NEGATIVE_INFINITY)
    }

    private class PointCloudChunk(val content: PointCloudContent, val origin: Vec3)

    companion object {
        /** Points per VBO chunk — bounds each GPU upload and sets the progressive-display batch. */
        private const val CHUNK_POINTS = 250_000
        private var seq = 0
    }
}
