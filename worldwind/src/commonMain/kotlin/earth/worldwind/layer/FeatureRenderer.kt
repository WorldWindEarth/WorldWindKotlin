package earth.worldwind.layer

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
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
import earth.worldwind.util.RingSimplifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a [CachedFeatureRow] (`(geometry, properties_json)`) into styled [Renderable]s, with the
 * same rules [BulkFeatureLayer] used inline. Shared so [BulkFeatureLayer] (bulk/viewport) and
 * [TiledFeatureLayer] (tiled) build renderables identically — Point → [Label]/[Placemark],
 * LineString → [Path], Polygon → [Polygon], and Multi/GeometryCollection geometries fan out.
 *
 * The config mirrors the layer's styling knobs; build a fresh renderer per load so it captures the
 * layer's current values.
 */
class FeatureRenderer(
    private val shapeAttributes: ShapeAttributes? = null,
    private val autoApplyStyle: Boolean = true,
    private val defaultLineColor: Color? = null,
    private val defaultFillColor: Color? = null,
    private val density: Float = DEFAULT_DENSITY,
    private val labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
    private val defaultAltitudeMode: AltitudeMode? = null,
    private val customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    /** RDP+radial simplify tolerance in degrees applied to every ring/line before it becomes
     *  [Position]s; `0.0` (default) passes geometry through unchanged. Only the tiled path
     *  ([TiledFeatureLayer]) sets it — full-resolution WFS geometry is mostly sub-pixel at a tile's
     *  zoom, so ~1px simplification cuts vertex count (and tile memory) ~5-6× with no visible change.
     *  The bulk/viewport path leaves it 0 (it has no per-tile zoom to key a tolerance off). */
    private val simplifyToleranceDeg: Double = 0.0,
) {
    /** Styled renderables for one row: [applyFeatureStyle] defaults (if [autoApplyStyle]) then
     *  [customLogicToApplyProperties], applied to each renderable the geometry expands to. */
    fun build(row: CachedFeatureRow): List<Renderable> {
        val properties = parseProperties(row.properties)
        return row.toRenderables(properties).onEach { renderable ->
            if (autoApplyStyle) renderable.applyAutoStyle(properties)
            renderable.customLogicToApplyProperties(properties)
        }
    }

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

    // [Polygon]'s primary constructor takes (positions, attributes). Build with the outer ring
    // first, then `addBoundary` for each hole. The no-arg `Polygon()` falls back to default
    // attributes (white fill / black outline) — avoid that path.
    private fun CachedGeometry.Polygon.toPolygon(): Polygon? {
        // Outer ring is simplified (big ring, where the memory win is); a collapsed outer ring is a
        // sub-pixel polygon, so drop it (no bbox fallback — its quad would paint a solid rectangle, the
        // blue-square artifact). Holes are NOT simplified: an island ring is small, so the tile-level
        // tolerance collapses it into a degenerate sliver that earcut renders as a spike or the area
        // gate drops (filling the island in as water) — and when a coarse tile is magnified the island
        // is visible on screen, so its detail must survive. Skipping it keeps full island fidelity for
        // little memory (holes are few vertices) while outer-ring simplification still bounds tile size.
        val outer = rings.firstOrNull()?.toPositions()?.takeIf { it.size >= 3 } ?: return null
        val polygon = shapeAttributes?.let { Polygon(outer, it) } ?: Polygon(outer)
        return polygon.apply {
            for (i in 1 until rings.size) rings[i].toPositions(simplify = false).let { if (it.size >= 3) addBoundary(it) }
            altitudeMode = altitudeModeFor(this@toPolygon.is3D)
            isFollowTerrain = altitudeMode == AltitudeMode.CLAMP_TO_GROUND
            pathType = PathType.LINEAR
        }
    }

    private fun pointRenderable(p: CachedGeometry.Point, props: LinkedHashMap<String, Any?>): Renderable {
        val position = Position.fromDegrees(p.y, p.x, p.z ?: 0.0)
        val name = NAME_ALIASES.firstNotNullOfOrNull { props[it] as? String }
        val icon = props["icon"] as? String
        // null OR zero z means "ground" (clamp); non-zero means absolute altitude unless overridden.
        val altMode = altitudeModeFor(is3D = (p.z ?: 0.0) != 0.0)
        // `name` without `icon` becomes a Label (gets [labelVisibilityThreshold]); else a Placemark.
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

    /** Inflate a ring/line's flat vertices into [Position]s, RDP-simplifying in place (x = lon°,
     *  y = lat°) to [simplifyToleranceDeg] first — passthrough when [simplify] is `false`, the
     *  tolerance is off, or the ring is too small to lose detail. Shares the [RingSimplifier] core
     *  with the MVT decoder, reading the [CachedGeometry.LineString.xy] array directly so no
     *  per-vertex object is allocated; a ring's closing vertex is kept (endpoints always survive).
     *
     *  Pass `simplify = false` for polygon holes: an island ring is small enough that the tile-level
     *  tolerance collapses it into a degenerate sliver (earcut spike, or area-gated into solid fill —
     *  the "island painted as water" bug), so holes keep every server vertex. */
    private fun CachedGeometry.LineString.toPositions(simplify: Boolean = true): List<Position> {
        val n = size
        if (!simplify || simplifyToleranceDeg <= 0.0 || n < 4) {
            val out = ArrayList<Position>(n)
            for (i in 0 until n) out.add(Position.fromDegrees(yAt(i), xAt(i), zAt(i) ?: 0.0))
            return out
        }
        val keep = BooleanArray(n)
        RingSimplifier.simplify(
            n, simplifyToleranceDeg * simplifyToleranceDeg, keep,
            xAt = { xy[it * 2] }, yAt = { xy[it * 2 + 1] },
        )
        val out = ArrayList<Position>()
        for (i in 0 until n) if (keep[i]) out.add(Position.fromDegrees(yAt(i), xAt(i), zAt(i) ?: 0.0))
        return out
    }

    // `z = null` is the cache-boundary sentinel for "2D, clamp to ground"; a non-null z array is a
    // real altitude per vertex. Sources fill z per format.
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

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
