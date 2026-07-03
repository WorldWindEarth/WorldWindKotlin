package earth.worldwind.formats.shapefile

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TriangleMesh
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.http.DefaultHttpClient
import earth.worldwind.util.http.HttpDefaults
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-record customization returned by the [ShapefileLayerFactory] `shapeConfiguration`
 * callback. Match WebWorldWind's plain-object configuration: setting `attributes` /
 * `placemarkAttributes` overrides the defaults; setting `altitude` or `height` switches
 * polylines from `SurfacePolyline` to `Path` and polygons from `SurfacePolygon` to
 * extruded `Polygon`; returning `null` from the callback skips the record entirely.
 *
 * Note on surface shapes: WorldWindKotlin uses [Polygon] with
 * [AltitudeMode.CLAMP_TO_GROUND] in place of `SurfacePolygon`, and [Path] with
 * `CLAMP_TO_GROUND` in place of `SurfacePolyline`. The configuration's `altitude` of 0
 * (the default) produces a clamped shape; non-zero values produce a free-floating shape.
 */
data class ShapefileShapeConfiguration(
    val attributes: ShapeAttributes? = null,
    val highlightAttributes: ShapeAttributes? = null,
    val placemarkAttributes: PlacemarkAttributes? = null,
    val highlightPlacemarkAttributes: PlacemarkAttributes? = null,
    val altitudeMode: AltitudeMode = AltitudeMode.RELATIVE_TO_GROUND,
    val altitude: Double = 0.0,
    val height: Double = 0.0,
    val name: String? = null,
)

/**
 * Builds a [RenderableLayer] from an ESRI shapefile. Two entry points:
 *
 *  * [createLayer] (URL form) fetches `foo.shp` plus the `foo.dbf` and `foo.prj`
 *    sidecars over HTTP, matching WebWorldWind's URL+callback pattern.
 *  * [createLayer] (raw-bytes form) parses already-loaded buffers. Use this for
 *    bundled assets or any case where the caller is doing its own IO.
 *
 * The optional `shapeConfiguration` callback runs once per record (after attribute
 * join) and lets the caller swap in custom [ShapeAttributes] / [PlacemarkAttributes],
 * label the shape, extrude polygons, etc. Returning `null` drops the record.
 *
 * Renderables produced (matching WebWorldWind):
 *  * Point / PointZ / PointM → one [Placemark] per point
 *  * MultiPoint / MultiPointZ / MultiPointM → one [Placemark] per point
 *  * Polyline / PolylineZ / PolylineM → one [Path] per record (one per part)
 *  * Polygon / PolygonZ / PolygonM → one [Polygon] per record (parts as boundaries)
 */
object ShapefileLayerFactory {
    /** Property keys searched (in order) for a label name when the configuration
     *  callback doesn't supply one. Mirrors WebWorldWind's `name | Name | NAME` lookup. */
    private val nameKeys = listOf("name", "Name", "NAME")

    /**
     * Load a shapefile from a URL pointing at the `.shp` file. The factory will derive
     * the `.dbf` and `.prj` sidecar URLs by replacing the extension and fetch all three
     * concurrently-ish (sequentially, matching the reference). Sidecars that 404 or
     * fail to download are tolerated and the layer is built without them.
     *
     * @param shpUrl URL of the `.shp` file. Must end in `.shp` (case-insensitive).
     * @param displayName Optional layer display name.
     * @param shapeConfiguration Optional per-record customization callback.
     */
    suspend fun createLayer(
        shpUrl: String,
        displayName: String? = null,
        shapeConfiguration: (ShapefileRecord) -> ShapefileShapeConfiguration? = { ShapefileShapeConfiguration() },
    ): RenderableLayer {
        require(shpUrl.isNotEmpty()) { "shpUrl must not be empty" }
        val baseUrl = stripShpExtension(shpUrl)
        val sidecars = DefaultHttpClient(HttpDefaults.CONNECT_TIMEOUT_MS, HttpDefaults.REQUEST_TIMEOUT_MS).use { httpClient ->
            val shpBytes = httpClient.get(shpUrl) { expectSuccess = true }.readRawBytes()
            val dbfBytes = fetchOptional(httpClient, "$baseUrl.dbf")
                ?: fetchOptional(httpClient, "$baseUrl.DBF")
            val prjBytes = fetchOptional(httpClient, "$baseUrl.prj")
                ?: fetchOptional(httpClient, "$baseUrl.PRJ")
            val cpgBytes = fetchOptional(httpClient, "$baseUrl.cpg")
                ?: fetchOptional(httpClient, "$baseUrl.CPG")
            ShapefileSidecars(shpBytes, dbfBytes, prjBytes, cpgBytes)
        }
        return createLayer(sidecars.shp, sidecars.dbf, sidecars.prj, sidecars.cpg, displayName, shapeConfiguration)
    }

    private data class ShapefileSidecars(
        val shp: ByteArray,
        val dbf: ByteArray?,
        val prj: ByteArray?,
        val cpg: ByteArray?,
    )

    /**
     * Build a layer from already-loaded buffers. The DBF, PRJ, and CPG buffers are all
     * optional; if absent, attributes default to empty maps, the shapefile is assumed
     * to be geographic, and DBF text decoding falls back to Latin-1.
     */
    suspend fun createLayer(
        shpBytes: ByteArray,
        dbfBytes: ByteArray? = null,
        prjBytes: ByteArray? = null,
        cpgBytes: ByteArray? = null,
        displayName: String? = null,
        shapeConfiguration: (ShapefileRecord) -> ShapefileShapeConfiguration? = { ShapefileShapeConfiguration() },
    ): RenderableLayer = withContext(Dispatchers.Default) {
        val prj = prjBytes?.let { runCatching { PrjFile(decodeUtf8OrLatin1(it)) }.getOrNull() }
        val cpgText = cpgBytes?.let { decodeUtf8OrLatin1(it) }
        val dbf = dbfBytes?.let { runCatching { DBaseFile(it, cpgText = cpgText) }.getOrNull() }
        if (prj != null && prj.isProjectedCoordinateSystem && prj.projection !is PrjFile.Projection.Utm) {
            Logger.log(WARN, "Shapefile uses an unsupported projected coordinate system; coords passed through unchanged")
        }
        val shapefile = Shapefile(shpBytes, projection = prj, attributes = dbf)
        buildLayer(shapefile, displayName, shapeConfiguration)
    }

    private fun buildLayer(
        shapefile: Shapefile,
        displayName: String?,
        shapeConfiguration: (ShapefileRecord) -> ShapefileShapeConfiguration?,
    ): RenderableLayer {
        val layer = RenderableLayer(displayName).apply { isPickEnabled = false }
        emitRecordRenderables(shapefile, shapeConfiguration) { _, renderable ->
            layer.addRenderable(renderable)
        }
        return layer
    }

    /**
     * Iterate [shapefile] records, run [shapeConfiguration] per record, and pass each resulting
     * [Renderable] back through [consumer] alongside the originating
     * record. The base [createLayer] entry points use this internally; bulk feature sources
     * (e.g. [earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource]) call it directly to
     * intercept each renderable + its DBF attributes for cache encoding without rebuilding the
     * shape-construction logic.
     */
    fun emitRecordRenderables(
        shapefile: Shapefile,
        shapeConfiguration: (ShapefileRecord) -> ShapefileShapeConfiguration? = { ShapefileShapeConfiguration() },
        consumer: (record: ShapefileRecord, renderable: Renderable) -> Unit,
    ) {
        for (record in shapefile.records) {
            val config = shapeConfiguration(record) ?: continue
            when {
                record.isPointType -> addPlacemarks(record, config) { r -> consumer(record, r) }
                record.isMultiPointType -> addPlacemarks(record, config) { r -> consumer(record, r) }
                record.isPolylineType -> addPolyline(record, config) { r -> consumer(record, r) }
                record.isPolygonType -> addPolygon(record, config) { r -> consumer(record, r) }
                record.isMultiPatchType -> addMultiPatch(record, config) { r -> consumer(record, r) }
            }
        }
    }

    private fun addPlacemarks(record: ShapefileRecord, config: ShapefileShapeConfiguration, consumer: (Renderable) -> Unit) {
        val placemarkAttributes = config.placemarkAttributes ?: PlacemarkAttributes()
        val label = config.name ?: deriveLabel(record)
        for (part in record.parts) {
            var i = 0
            while (i + 1 < part.size) {
                val lon = part[i]
                val lat = part[i + 1]
                val altitude = if (config.altitude != 0.0) config.altitude else 0.0
                val placemark = Placemark(Position.fromDegrees(lat, lon, altitude), placemarkAttributes, label).apply {
                    altitudeMode = config.altitudeMode
                    config.highlightPlacemarkAttributes?.let { highlightAttributes = it }
                }
                consumer(placemark)
                i += 2
            }
        }
    }

    private fun addPolyline(record: ShapefileRecord, config: ShapefileShapeConfiguration, consumer: (Renderable) -> Unit) {
        val attributes = config.attributes ?: ShapeAttributes()
        for ((partIndex, part) in record.parts.withIndex()) {
            val positions = pointsToPositions(record, partIndex, part, config)
            if (positions.isEmpty()) continue
            val path = Path(positions, attributes).apply {
                if (config.altitude == 0.0) {
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    isFollowTerrain = true
                } else {
                    altitudeMode = config.altitudeMode
                }
                config.highlightAttributes?.let { highlightAttributes = it }
                config.name?.let { displayName = it }
            }
            consumer(path)
        }
    }

    private fun addPolygon(record: ShapefileRecord, config: ShapefileShapeConfiguration, consumer: (Renderable) -> Unit) {
        val attributes = config.attributes ?: ShapeAttributes()

        // Shapefile polygon parts use ring-winding to mark outer vs inner rings:
        //   CW (clockwise, in geographic Y-up = positive signed area below) → outer
        //   CCW (counter-clockwise, negative signed area)                    → inner (hole)
        // Inner rings always follow their outer ring in the parts array. We emit one
        // [Polygon] per outer ring with the CCW followers attached as additional
        // boundaries (which [Polygon] tessellates as holes). Multi-island countries
        // (Indonesia, the US, Russia) thus become many Polygons rather than one
        // many-chain Polygon — necessary because WorldWindKotlin's Polygon caps the
        // outline-chain count at 5.
        var pendingOuter: List<Position>? = null
        var pendingHoles: MutableList<List<Position>>? = null

        fun emit() {
            val outer = pendingOuter ?: return
            val polygon = Polygon(outer, attributes).apply {
                pendingHoles?.forEach { addBoundary(it) }
                when {
                    config.height != 0.0 -> { isExtrude = true; altitudeMode = config.altitudeMode }
                    config.altitude == 0.0 -> {
                        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                        isFollowTerrain = true
                    }
                    else -> altitudeMode = config.altitudeMode
                }
                config.highlightAttributes?.let { highlightAttributes = it }
                config.name?.let { displayName = it }
            }
            consumer(polygon)
            pendingOuter = null
            pendingHoles = null
        }

        for ((partIndex, part) in record.parts.withIndex()) {
            // Shapefile rings repeat the first vertex as the last; drop the duplicate
            // before computing winding (so the shoelace integrates a closed but
            // non-degenerate polygon).
            val positions = pointsToPositions(record, partIndex, part, config, dropLast = true)
            if (positions.size < 3) continue
            if (isClockwise(positions)) {
                // New outer ring — flush any previous outer+holes group, then start fresh.
                emit()
                pendingOuter = positions
            } else {
                // Hole attaching to the previous outer ring. If no prior outer (malformed
                // file or just the first ring being CCW), treat it as outer instead of
                // dropping it — that matches WebWorldWind's "best-effort render" behavior.
                if (pendingOuter == null) {
                    pendingOuter = positions
                } else {
                    (pendingHoles ?: mutableListOf<List<Position>>().also { pendingHoles = it })
                        .add(positions)
                }
            }
        }
        emit()
    }

    private fun addMultiPatch(record: ShapefileRecord, config: ShapefileShapeConfiguration, consumer: (Renderable) -> Unit) {
        val partTypes = record.partTypes ?: return
        val zValues = record.zValues
        val attributes = config.attributes ?: ShapeAttributes()

        val positions = ArrayList<Position>()
        val indices = ArrayList<Int>()
        var partBase = 0
        var pointIndex = 0
        for ((partIndex, part) in record.parts.withIndex()) {
            val partLength = part.size / 2
            for (i in 0 until partLength) {
                val lon = part[i * 2]
                val lat = part[i * 2 + 1]
                val z = zValues?.getOrNull(pointIndex + i) ?: 0.0
                val altitude = when {
                    config.height != 0.0 -> config.height
                    config.altitude != 0.0 -> config.altitude
                    else -> z
                }
                positions.add(Position.fromDegrees(lat, lon, altitude))
            }
            when (partTypes[partIndex]) {
                MultiPatchPartType.TRIANGLE_STRIP -> {
                    for (i in 0 until partLength - 2) {
                        if (i % 2 == 0) {
                            indices.add(partBase + i)
                            indices.add(partBase + i + 1)
                            indices.add(partBase + i + 2)
                        } else {
                            indices.add(partBase + i + 1)
                            indices.add(partBase + i)
                            indices.add(partBase + i + 2)
                        }
                    }
                }
                MultiPatchPartType.TRIANGLE_FAN -> {
                    for (i in 1 until partLength - 1) {
                        indices.add(partBase)
                        indices.add(partBase + i)
                        indices.add(partBase + i + 1)
                    }
                }
                MultiPatchPartType.OUTER_RING, MultiPatchPartType.INNER_RING,
                MultiPatchPartType.FIRST_RING, MultiPatchPartType.RING -> {
                    for (i in 1 until partLength - 1) {
                        indices.add(partBase)
                        indices.add(partBase + i)
                        indices.add(partBase + i + 1)
                    }
                }
            }
            partBase += partLength
            pointIndex += partLength
        }

        if (positions.isEmpty() || indices.size < 3) return
        if (positions.size > 65_536) {
            Logger.log(WARN, "MultiPatch record ${record.recordNumber} has ${positions.size} positions; exceeds TriangleMesh cap, skipped")
            return
        }
        val mesh = TriangleMesh(positions.toTypedArray(), indices.toIntArray(), attributes).apply {
            altitudeMode = config.altitudeMode
            config.highlightAttributes?.let { highlightAttributes = it }
            config.name?.let { displayName = it }
        }
        consumer(mesh)
    }

    /**
     * Sign of the shoelace integral over [positions]. In geographic (Y-up = north)
     * coordinates a clockwise ring integrates to a positive value; the shapefile spec
     * uses CW for outer rings and CCW for holes.
     */
    private fun isClockwise(positions: List<Position>): Boolean {
        var sum = 0.0
        for (i in positions.indices) {
            val a = positions[i]
            val b = positions[(i + 1) % positions.size]
            sum += (b.longitude.inDegrees - a.longitude.inDegrees) *
                (b.latitude.inDegrees + a.latitude.inDegrees)
        }
        return sum > 0
    }

    /** Convert a flat `[lon0, lat0, lon1, lat1, …]` array into [Position]s, honoring the
     *  configuration's altitude/height (height wins) and the record's per-point Z values
     *  when the shapefile carries Z. */
    private fun pointsToPositions(
        record: ShapefileRecord,
        partIndex: Int,
        part: DoubleArray,
        config: ShapefileShapeConfiguration,
        dropLast: Boolean = false,
    ): List<Position> {
        val pointCount = part.size / 2
        val effectiveCount = if (dropLast && pointCount > 1) pointCount - 1 else pointCount
        if (effectiveCount <= 0) return emptyList()

        val zValues = record.zValues
        val zOffset = if (zValues != null) partStartIndex(record, partIndex) else 0
        val configAltitude = when {
            config.height != 0.0 -> config.height
            config.altitude != 0.0 -> config.altitude
            else -> 0.0
        }

        val positions = ArrayList<Position>(effectiveCount)
        for (i in 0 until effectiveCount) {
            val lon = part[i * 2]
            val lat = part[i * 2 + 1]
            val zAlt = zValues?.getOrNull(zOffset + i) ?: 0.0
            val altitude = if (configAltitude != 0.0) configAltitude else zAlt
            positions.add(Position.fromDegrees(lat, lon, altitude))
        }
        return positions
    }

    /** Z values are stored as one flat array across all parts. To index into them for
     *  a given part we sum the point counts of all earlier parts. */
    private fun partStartIndex(record: ShapefileRecord, partIndex: Int): Int {
        var sum = 0
        for (i in 0 until partIndex) sum += record.parts[i].size / 2
        return sum
    }

    private fun deriveLabel(record: ShapefileRecord): String? {
        if (record.attributes.isEmpty()) return null
        for (key in nameKeys) {
            val value = record.attributes[key] ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private suspend fun fetchOptional(httpClient: io.ktor.client.HttpClient, url: String): ByteArray? = try {
        httpClient.get(url) { expectSuccess = true }.readRawBytes()
    } catch (e: Throwable) {
        Logger.log(WARN, "Optional shapefile sidecar not loaded ($url): ${e.message}")
        null
    }

    private fun stripShpExtension(url: String): String {
        val lower = url.lowercase()
        val idx = lower.lastIndexOf(".shp")
        return if (idx >= 0) url.substring(0, idx) else url
    }

    /** PRJ files are ASCII / Latin-1 in practice. Decode as UTF-8 first (correct for any
     *  ASCII subset); if that fails on a stray high byte, fall back to a Latin-1 mapping. */
    private fun decodeUtf8OrLatin1(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: Throwable) {
        buildString(bytes.size) { for (b in bytes) append(Char(b.toInt() and 0xFF)) }
    }
}
