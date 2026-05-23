package earth.worldwind.layer.buildings

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Sector
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Renders OpenStreetMap-derived schematic 3D buildings. Footprints + height tags are fetched
 * on demand for the visible region via an [OsmBuildingsSource] (defaults to [OverpassBuildingsSource]),
 * locally extruded into [Polygon]s with [AltitudeMode.RELATIVE_TO_GROUND], and cached per
 * slippy-map tile. Visual approximation of Cesium OSM Buildings — fully open, no API key.
 *
 * ```
 * val buildings = OsmBuildingsLayer()
 * worldWindow.engine.layers.addLayer(buildings)
 * // ...later:
 * buildings.close()
 * ```
 *
 * Tile selection uses a center-radius window: at each frame, the (2×[tileRadius]+1)² tiles
 * around the camera's lookAt point (falling back to the camera lat/lon) are scheduled for
 * fetch. At zoom 15 a radius of 4 covers ~10 km square, which fills the visible area for any
 * reasonable camera tilt at building-scale altitudes. Fetching is gated by [maxActiveAltitude]
 * (30 km by default), above which the layer is a no-op.
 *
 * Style every building uniformly via [attributes]; for per-building styling, subclass and
 * override [toPolygon]. Always call [close] to cancel in-flight fetches.
 */
open class OsmBuildingsLayer(
    val source: OsmBuildingsSource = OverpassBuildingsSource(),
    val tileZoom: Int = 15,
    val tileRadius: Int = 4,
    val maxLoadedTiles: Int = 256,
    maxConcurrentFetches: Int = 2,
    /**
     * When true, [toPolygon] consults [OsmColors] on each [OsmBuilding] and overrides the
     * polygon's [interiorColor][ShapeAttributes.interiorColor] with the colour derived from
     * `building:colour` / `colour` / `building:material` tags. Buildings without any of those
     * tags keep [attributes] unchanged. Off by default for Cesium-style uniform-gray output.
     */
    val useOsmColors: Boolean = false,
    displayName: String? = "OSM Buildings",
) : AbstractLayer(displayName) {

    /** Shape attributes applied to every building polygon. Mutating between frames is safe. */
    var attributes: ShapeAttributes = defaultBuildingAttributes()

    private val semaphore = Semaphore(maxConcurrentFetches.coerceAtLeast(1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val results = Channel<TileResult>(capacity = Channel.UNLIMITED)
    private val tiles = LruMemoryCache<TileKey, List<Polygon>>(maxLoadedTiles.toLong())
    private val pending = HashSet<TileKey>()
    private var isClosed = false

    init {
        maxActiveAltitude = 30_000.0
        // Buildings are scenery, not interactive. Disabling picking at the layer level keeps
        // them out of the drag detector (Polygon implements Movable, so without this they'd
        // shift under the cursor with the standard SelectDragCallback wiring).
        isPickEnabled = false
    }

    override fun doRender(rc: RenderContext) {
        drainResults()

        // Center on lookAt when available (true look-at point on the globe), else fall back to
        // the camera's lat/lon. terrain.sector was too brittle here — at building zoom it
        // routinely exceeds any reasonable per-frame tile cap.
        val center = rc.lookAtPosition ?: rc.camera.position
        val n = 1 shl tileZoom
        val centerXY = lonLatToTile(center.longitude.inDegrees, center.latitude.inDegrees, tileZoom)
        val cx = centerXY.first
        val cy = centerXY.second

        for (dy in -tileRadius..tileRadius) {
            val y = cy + dy
            if (y !in 0 until n) continue
            for (dx in -tileRadius..tileRadius) {
                // Wrap longitudinally so a tile-radius window straddling the antimeridian still works.
                val x = ((cx + dx) % n + n) % n
                processTile(rc, TileKey(tileZoom, x, y))
            }
        }
    }

    private fun processTile(rc: RenderContext, key: TileKey) {
        val polygons = tiles[key]
        if (polygons != null) {
            for (i in polygons.indices) {
                try {
                    polygons[i].render(rc)
                } catch (e: Exception) {
                    logMessage(ERROR, "OsmBuildingsLayer", "doRender",
                        "Exception while rendering building polygon", e)
                }
            }
        } else if (!isClosed && pending.add(key)) {
            scope.launch { fetch(key) }
        }
    }

    /**
     * Cancels in-flight fetches and frees the cache. Idempotent. The layer cannot be reused
     * after [close].
     */
    open fun close() {
        if (isClosed) return
        isClosed = true
        scope.cancel()
        results.close()
        tiles.clear()
        pending.clear()
    }

    /**
     * Wrap one [OsmBuilding] in an extruded [Polygon]. Override to customize per-building
     * style (height-based color, name labels, lighting, …).
     */
    protected open fun toPolygon(building: OsmBuilding): Polygon {
        val effectiveAttributes = if (useOsmColors) {
            OsmColors.resolve(building.tags)?.let { color ->
                // Per-polygon copy so the layer-wide [attributes] is not mutated. Allocation
                // is acceptable - one ShapeAttributes per coloured building, only on the
                // background fetch path.
                ShapeAttributes(attributes).apply { interiorColor = color }
            } ?: attributes
        } else attributes
        return Polygon(building.outerRing, effectiveAttributes).apply {
            isExtrude = true
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
            baseAltitude = building.minHeight
            building.name?.let { displayName = it }
            for (hole in building.innerRings) addBoundary(hole)
        }
    }

    private fun drainResults() {
        while (true) {
            val result = results.tryReceive().getOrNull() ?: return
            pending.remove(result.key)
            val polygons = result.polygons ?: continue
            // Weight = 1 per tile so [maxLoadedTiles] really is a tile count. Earlier this used
            // `polygons.size`, which trashed the cache: a dense Manhattan tile (500+ buildings)
            // would exceed the 256-unit capacity and evict every other tile in one put, and the
            // next tile's put would evict it in turn — visible as random tile flicker.
            tiles.put(result.key, polygons, 1)
        }
    }

    private suspend fun fetch(key: TileKey) {
        val polygons = try {
            semaphore.withPermit { source.fetchBuildings(key.sector).map(::toPolygon) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Visible failure: silent retries on the next render are still possible, but the
            // user/developer can see *why* tiles aren't appearing. Public Overpass mirrors
            // commonly return 429 / 503 under load; a self-hosted endpoint is the fix for
            // production traffic.
            logMessage(WARN, "OsmBuildingsLayer", "fetch",
                "Failed to fetch tile $key: ${e::class.simpleName}: ${e.message}")
            null
        }
        results.trySend(TileResult(key, polygons))
        // Wake the render thread so the just-finished tile renders without waiting for a pan.
        // Also fires on failures so doRender drops the key from `pending` and can retry.
        WorldWind.requestRedraw()
    }

    private data class TileKey(val z: Int, val x: Int, val y: Int) {
        val sector: Sector get() = tileToSector(z, x, y)
    }

    private data class TileResult(val key: TileKey, val polygons: List<Polygon>?)

    private companion object {
        // Latitude at which Web Mercator y reaches ±π. Standard slippy-tile clamp.
        const val MAX_MERCATOR_LAT = 85.0511287798066

        fun defaultBuildingAttributes(): ShapeAttributes = ShapeAttributes().apply {
            interiorColor = Color(red = 0.80f, green = 0.80f, blue = 0.78f, alpha = 1f)
            outlineColor = Color(red = 0.45f, green = 0.45f, blue = 0.43f, alpha = 1f)
            outlineWidth = 1f
            // Flat per-face Lambertian shading distinguishes the wall faces of a building box
            // (otherwise all four sides paint the same gray and read as one flat shape).
            isLightingEnabled = true
        }

        fun lonLatToTile(lonDegrees: Double, latDegrees: Double, zoom: Int): Pair<Int, Int> {
            val n = 1 shl zoom
            val x = ((lonDegrees + 180.0) / 360.0 * n).toInt()
            val latRad = latDegrees.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT) * PI / 180.0
            val y = ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
            return x to y
        }

        fun tileToSector(zoom: Int, x: Int, y: Int): Sector {
            val n = 1 shl zoom
            val west = x.toDouble() / n * 360.0 - 180.0
            val east = (x + 1).toDouble() / n * 360.0 - 180.0
            val north = atan(sinh(PI * (1 - 2 * y.toDouble() / n))) * 180.0 / PI
            val south = atan(sinh(PI * (1 - 2 * (y + 1).toDouble() / n))) * 180.0 / PI
            return Sector.fromDegrees(south, west, north - south, east - west)
        }
    }
}
