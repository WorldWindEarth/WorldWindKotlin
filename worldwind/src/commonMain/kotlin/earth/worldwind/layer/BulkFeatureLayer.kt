package earth.worldwind.layer

import earth.worldwind.formats.geojson.NAME_ALIASES
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.source.BulkFeatureSource
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Vector-feature layer driven by a [BulkFeatureSource]. The source delivers
 * `(geometry, properties_json)` rows from one of:
 *   * network WFS: [earth.worldwind.ogc.wfs.WfsBulkFeatureSource] → WFS GetFeature pipeline.
 *   * network Shapefile: [earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource] →
 *     one HTTP fetch of the .shp/.dbf/.prj triple.
 *   * cache: [earth.worldwind.layer.cache.CachedBulkFeatureSource] wrapping a
 *     [earth.worldwind.layer.cache.FeatureStore].
 *   * cache-first then network: `CachedBulkFeatureSource(WfsBulkFeatureSource(...), store)`.
 *
 * "Bulk" because one source fetch pulls *every* feature in the layer; there is no per-tile
 * addressing. Compare:
 *   * [earth.worldwind.layer.mvt.MvtVectorLayer] — bytes-per-tile vector source.
 *   * [earth.worldwind.layer.buildings.OsmBuildingsLayer] — features-per-tile source.
 *
 * Styling lives entirely on the layer side — [customLogicToApplyProperties] fires once per
 * decoded renderable with that feature's properties map, identically for both network and
 * cache paths.
 *
 * Point features become a [Label] when `properties.name` is non-blank and `properties.icon`
 * is null; otherwise a [Placemark]. LineStrings become [Path]s, Polygons become [Polygon]s.
 */
open class BulkFeatureLayer(
    var source: BulkFeatureSource,
    displayName: String? = null,
    /**
     * Default [ShapeAttributes] applied to every [Polygon] / [Path] this layer builds.
     * `null` leaves the renderable at its own default (white fill, black outline). Set this
     * to colour an entire layer in one go without writing a per-feature lambda — the per-
     * feature [customLogicToApplyProperties] still gets a chance to override attributes
     * after this default is installed.
     */
    var shapeAttributes: ShapeAttributes? = null,
    var customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
) : RenderableLayer(displayName), VectorLayer {

    /**
     * Pull rows from [source] and atomically replace this layer's renderables. Accumulates
     * the full set before swapping in to avoid mid-load flicker (paginated WFS responses
     * still complete in one swap). Apply [customLogicToApplyProperties] per row.
     */
    open suspend fun load() {
        val incoming = ArrayList<Renderable>()
        source.fetchAll().collect { row ->
            val properties = parseProperties(row.properties)
            val renderable = row.toRenderable(properties) ?: return@collect
            renderable.customLogicToApplyProperties(properties)
            incoming += renderable
        }
        clearRenderables()
        addAllRenderables(incoming)
    }

    private fun CachedFeatureRow.toRenderable(props: LinkedHashMap<String, Any?>): Renderable? = when (val g = geometry) {
        null -> null
        is CachedGeometry.Point -> pointRenderable(g, props)
        is CachedGeometry.LineString -> {
            val path = shapeAttributes?.let { Path(g.toPositions(), it) } ?: Path(g.toPositions())
            path.apply {
                altitudeMode = if (g.is3D) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = !g.is3D
                pathType = PathType.LINEAR
            }
        }
        is CachedGeometry.Polygon -> {
            // [Polygon]'s primary constructor takes (positions, attributes). Build with the
            // outer ring first, then `addBoundary` for each hole. The "white fill / black
            // outline" reported on first load came from missing this construction path —
            // the new pipeline used to call the no-arg `Polygon()` which falls back to a
            // default ShapeAttributes instance.
            val rings = g.rings
            val outer = rings.firstOrNull()?.toPositions() ?: return null
            val polygon = shapeAttributes?.let { Polygon(outer, it) } ?: Polygon(outer)
            polygon.apply {
                for (i in 1 until rings.size) addBoundary(rings[i].toPositions())
                altitudeMode = if (g.is3D) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = !g.is3D
                pathType = PathType.LINEAR
            }
        }
    }

    private fun pointRenderable(p: CachedGeometry.Point, props: LinkedHashMap<String, Any?>): Renderable {
        val position = Position.fromDegrees(p.y, p.x, p.z ?: 0.0)
        val name = NAME_ALIASES.firstNotNullOfOrNull { props[it] as? String }
        val icon = props["icon"] as? String
        // Same ground-vs-elevated heuristic as the polyline/polygon branches: null OR zero z
        // means "ground" (clamp), non-zero means absolute altitude.
        val altMode = if ((p.z ?: 0.0) != 0.0) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND
        // Mirrors GeoJsonLayerFactory's Point branch: a `name` without `icon` becomes a
        // Label, otherwise a Placemark.
        return if (icon.isNullOrBlank() && !name.isNullOrBlank()) {
            Label(position, name).apply { altitudeMode = altMode }
        } else {
            Placemark(position, label = name).apply { altitudeMode = altMode }
        }
    }

    private fun CachedGeometry.LineString.toPositions(): List<Position> =
        points.map { Position.fromDegrees(it.y, it.x, it.z ?: 0.0) }

    // `z = null` is the cache-boundary sentinel for "2D geometry, clamp to ground"; `z != null`
    // (including legitimate `z = 0.0` — sea-level buoys, sea-floor points) is honoured as a
    // real altitude. Bulk feature sources are responsible for filling z correctly per format
    // (e.g. [earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource] consults
    // `shapefile.shapeType.isZ`).
    private val CachedGeometry.LineString.is3D: Boolean get() = points.any { it.z != null }
    private val CachedGeometry.Polygon.is3D: Boolean get() = rings.any { it.is3D }

    private fun parseProperties(text: String?): LinkedHashMap<String, Any?> {
        if (text == null) return LinkedHashMap()
        val obj = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return LinkedHashMap()
        val result = LinkedHashMap<String, Any?>(obj.size)
        obj.forEach { (k, v) -> result[k] = jsonValueToAny(v) }
        return result
    }

    private fun jsonValueToAny(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.content.toDoubleOrNull() ?: element.content.toLongOrNull() ?: element.content
        }
        is JsonObject -> {
            val map = LinkedHashMap<String, Any?>(element.size)
            element.forEach { (k, v) -> map[k] = jsonValueToAny(v) }
            map
        }
        is JsonArray -> element.map { jsonValueToAny(it) }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
