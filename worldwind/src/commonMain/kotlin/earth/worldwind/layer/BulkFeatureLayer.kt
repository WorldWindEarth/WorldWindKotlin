package earth.worldwind.layer

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.source.BulkFeatureSource
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.layer.source.DEFAULT_DENSITY
import earth.worldwind.layer.source.DEFAULT_LABEL_VISIBILITY_THRESHOLD
import earth.worldwind.layer.source.NAME_ALIASES
import earth.worldwind.layer.source.applyFeatureStyle
import earth.worldwind.render.Color
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
import kotlinx.serialization.json.JsonElement

/**
 * Vector-feature layer driven by a [BulkFeatureSource]. The source delivers
 * `(geometry, properties_json)` rows from one of:
 *   * GeoJSON document: [earth.worldwind.layer.source.GeoJsonBulkFeatureSource].
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
 * Styling fires twice per feature when [autoApplyStyle] is `true` (default): first
 * [applyFeatureStyle] reads GeoJSON simplestyle keys (`stroke`, `fill`, `icon`, etc.) onto
 * the renderable, then [customLogicToApplyProperties] runs and can override anything.
 * Disable with `autoApplyStyle = false` when the source emits properties that aren't
 * simplestyle and you don't want the no-match defaults applied.
 *
 * Point features become a [Label] when the resolved feature name is non-blank and the
 * `icon` property is absent; otherwise a [Placemark]. LineStrings become [Path]s, Polygons
 * become [Polygon]s, MultiPolygons fan out to N [Polygon]s sharing one properties map.
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
    /** Apply [applyFeatureStyle] from the feature's properties before
     *  [customLogicToApplyProperties] runs. Off for sources whose properties are pure data
     *  with no styling intent (some WFS endpoints). */
    var autoApplyStyle: Boolean = true,
    /** Fallback line colour written when a feature has no `stroke` property. `null`
     *  (default) leaves the renderable's existing outline alone — preserves the layer's
     *  [shapeAttributes] template (e.g. Shapefile's translucent fill) when the source
     *  has no simplestyle keys. */
    var defaultLineColor: Color? = null,
    /** Fallback fill colour written when a feature has no `fill` property. Same null
     *  semantics as [defaultLineColor]. */
    var defaultFillColor: Color? = null,
    /** Multiplier applied to placemark icon scale — pass per-display density to keep
     *  icons consistent across DPI. Default `1.0f` leaves scale unchanged. */
    var density: Float = DEFAULT_DENSITY,
    /** Visibility threshold (eye-distance, meters) set on every emitted [Label]. `0.0`
     *  (default) keeps labels visible at any distance. */
    var labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
    /** Force every emitted renderable's altitude mode. `null` (default) picks
     *  [AltitudeMode.ABSOLUTE] when the cached geometry has any non-null z, else
     *  [AltitudeMode.CLAMP_TO_GROUND]. Set explicitly when the source has z values that
     *  shouldn't be rendered as altitude (e.g. shapefile measure-z, GeoJSON elevation
     *  values you want flattened). */
    var defaultAltitudeMode: AltitudeMode? = null,
    var customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
) : RenderableLayer(displayName), VectorLayer {

    /**
     * Pull rows from [source] and atomically replace this layer's renderables. Accumulates
     * the full set before swapping in to avoid mid-load flicker (paginated WFS responses
     * still complete in one swap). Per renderable: install [applyFeatureStyle] defaults if
     * [autoApplyStyle], then run [customLogicToApplyProperties].
     */
    open suspend fun load() {
        val incoming = ArrayList<Renderable>()
        source.fetchAll().collect { row ->
            val properties = parseProperties(row.properties)
            for (renderable in row.toRenderables(properties)) {
                if (autoApplyStyle) renderable.applyAutoStyle(properties)
                renderable.customLogicToApplyProperties(properties)
                incoming += renderable
            }
        }
        clearRenderables()
        addAllRenderables(incoming)
    }

    /** Release the [source]'s resources (e.g. its reused HTTP client). Call when discarding the
     *  layer so a network-backed source doesn't leak its client. Idempotent. */
    open fun close() = source.close()

    private fun Renderable.applyAutoStyle(properties: LinkedHashMap<String, Any?>) {
        when (this) {
            is Path -> applyFeatureStyle(properties, defaultLineColor, defaultFillColor)
            is Polygon -> applyFeatureStyle(properties, defaultLineColor, defaultFillColor)
            is Placemark -> applyFeatureStyle(properties, density)
            is Label -> applyFeatureStyle(properties)
        }
    }

    private fun CachedFeatureRow.toRenderables(props: LinkedHashMap<String, Any?>): List<Renderable> =
        geometry?.let { renderablesFor(it, props) } ?: emptyList()

    // Multi* / GeometryCollection fan out to N renderables that share the one properties map;
    // GeometryCollection recurses so nested collections are handled too.
    private fun renderablesFor(g: CachedGeometry, props: LinkedHashMap<String, Any?>): List<Renderable> =
        when (g) {
            is CachedGeometry.Point -> listOf(pointRenderable(g, props))
            is CachedGeometry.LineString -> listOf(g.toPath())
            is CachedGeometry.Polygon -> listOfNotNull(g.toPolygon())
            is CachedGeometry.MultiPolygon -> g.polygons.mapNotNull { it.toPolygon() }
            is CachedGeometry.MultiPoint -> g.points.map { pointRenderable(it, props) }
            is CachedGeometry.MultiLineString -> g.lines.map { it.toPath() }
            is CachedGeometry.GeometryCollection -> g.geometries.flatMap { renderablesFor(it, props) }
        }

    private fun CachedGeometry.LineString.toPath(): Path {
        val positions = toPositions()
        val path = shapeAttributes?.let { Path(positions, it) } ?: Path(positions)
        return path.apply {
            altitudeMode = altitudeModeFor(this@toPath.is3D)
            isFollowTerrain = altitudeMode == AltitudeMode.CLAMP_TO_GROUND
            pathType = PathType.LINEAR
        }
    }

    // [Polygon]'s primary constructor takes (positions, attributes). Build with the outer
    // ring first, then `addBoundary` for each hole. Calling the no-arg `Polygon()` falls
    // back to default ShapeAttributes (white fill / black outline) — avoid that path.
    private fun CachedGeometry.Polygon.toPolygon(): Polygon? {
        val outer = rings.firstOrNull()?.toPositions() ?: return null
        val polygon = shapeAttributes?.let { Polygon(outer, it) } ?: Polygon(outer)
        return polygon.apply {
            for (i in 1 until rings.size) addBoundary(rings[i].toPositions())
            altitudeMode = altitudeModeFor(this@toPolygon.is3D)
            isFollowTerrain = altitudeMode == AltitudeMode.CLAMP_TO_GROUND
            pathType = PathType.LINEAR
        }
    }

    private fun pointRenderable(p: CachedGeometry.Point, props: LinkedHashMap<String, Any?>): Renderable {
        val position = Position.fromDegrees(p.y, p.x, p.z ?: 0.0)
        val name = NAME_ALIASES.firstNotNullOfOrNull { props[it] as? String }
        val icon = props["icon"] as? String
        // null OR zero z means "ground" (clamp); non-zero means absolute altitude unless
        // the layer has [defaultAltitudeMode] override.
        val altMode = altitudeModeFor(is3D = (p.z ?: 0.0) != 0.0)
        // `name` without `icon` becomes a Label, otherwise a Placemark — the Label form gets
        // the layer's [labelVisibilityThreshold] applied so callers can hide labels at
        // distance without writing per-feature logic.
        return if (icon.isNullOrBlank() && !name.isNullOrBlank()) {
            Label(position, name).apply {
                altitudeMode = altMode
                if (labelVisibilityThreshold != 0.0) visibilityThreshold = labelVisibilityThreshold
            }
        } else {
            Placemark(position, label = name).apply { altitudeMode = altMode }
        }
    }

    private fun altitudeModeFor(is3D: Boolean): AltitudeMode =
        defaultAltitudeMode ?: if (is3D) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND

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

    private fun jsonValueToAny(element: JsonElement): Any? = when (element) {
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
