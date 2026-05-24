package earth.worldwind.layer.mvt

import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.Color
import earth.worldwind.shape.ShapeAttributes

/**
 * Decides how each MVT feature should be drawn: for polygons and lines, what
 * [ShapeAttributes] to apply; `null` skips the feature entirely.
 *
 * Two implementation styles ship in the package: hand-coded ([DefaultMvtStyle],
 * [OpenTopoMapMvtStyle]) and rule-based with filter/zoom support ([MvtRuleBasedStyle],
 * built by the [mvtStyle] DSL).
 *
 * Implementations should be pure and stateless — [MvtVectorLayer] resolves the style
 * once per feature during background tile assembly, off the render thread.
 */
fun interface MvtStyle {
    /**
     * Resolve attributes for one feature. [layerName] is the MVT layer (e.g. `water`,
     * `transportation`, `building`); [geometryType] gates polygon-vs-line attribute choice.
     * [properties] is the inflated feature property map.
     *
     * Return `null` to omit the feature from the output.
     */
    fun styleFor(
        layerName: String,
        geometryType: MvtGeometryType,
        properties: Map<String, Any?>,
    ): ShapeAttributes?

    /**
     * Optional paint order within a tile. Features with smaller [zOrderFor] paint first,
     * features with larger values paint over them. Default 0 — every feature shares the same
     * z-layer, and the order falls through to MVT-server-determined layer iteration order
     * (good enough for OpenMapTiles-schema servers that already order water → landuse →
     * roads → buildings sensibly).
     *
     * Standard convention: `MvtStyle.Z_*` constants — see the companion. Override per-style
     * when the default order conflicts with the visual intent.
     */
    fun zOrderFor(
        layerName: String,
        geometryType: MvtGeometryType,
        properties: Map<String, Any?>,
    ): Int = 0

    companion object {
        // Canonical z-order bands. Spacing in tens leaves room for per-class adjustments
        // ([Z_ROAD_MOTORWAY] sits above [Z_ROAD_MINOR] without colliding with [Z_BUILDING]).
        // Apply to whichever MvtStyle wants explicit ordering; the layer just sorts ascending.
        const val Z_BACKGROUND = -10
        const val Z_WATER = 10
        const val Z_LANDCOVER = 20
        const val Z_LANDUSE = 25
        const val Z_PARK = 30
        const val Z_WATERWAY = 40
        const val Z_BUILDING = 50
        const val Z_AEROWAY = 55
        const val Z_BOUNDARY = 60
        const val Z_ROAD_TRACK = 70
        const val Z_ROAD_PATH = 71
        const val Z_ROAD_MINOR = 80
        const val Z_ROAD_TERTIARY = 82
        const val Z_ROAD_SECONDARY = 84
        const val Z_ROAD_PRIMARY = 86
        const val Z_ROAD_TRUNK = 88
        const val Z_ROAD_MOTORWAY = 90
        const val Z_RAIL = 95
        const val Z_LABEL = 100
    }
}

/**
 * Hardcoded style table approximating an OpenMapTiles "basic" look. Covers the layer names
 * commonly seen across OpenMapTiles, Maptiler v3, Mapbox v8, and Protomaps "basemaps" schemas.
 *
 * Decisions:
 *   - POINT features always return `null` — hand-coded styles don't carry label data; use
 *     [MvtRuleBasedStyle] for labels.
 *   - Roads are styled by `class`/`kind` property where available, with a generic fallback.
 *   - Outlines are off for area fills — outlining a dense mesh of tiny polygons drops the
 *     framerate without improving readability.
 *   - Shadows are off layer-wide; vector-tile features are flat surface decals, not occluders.
 */
object DefaultMvtStyle : MvtStyle {

    override fun zOrderFor(
        layerName: String,
        geometryType: MvtGeometryType,
        properties: Map<String, Any?>,
    ): Int {
        val kind = properties["class"] ?: properties["kind"]
        return when (layerName) {
            "water", "ocean", "water_polygons", "river_polygons" -> MvtStyle.Z_WATER
            "waterway", "water_line", "water_lines" -> MvtStyle.Z_WATERWAY
            "landcover", "land" -> MvtStyle.Z_LANDCOVER
            "landuse", "sites" -> MvtStyle.Z_LANDUSE
            "park", "national_park" -> MvtStyle.Z_PARK
            "building", "buildings" -> MvtStyle.Z_BUILDING
            "boundary", "admin", "boundaries" -> MvtStyle.Z_BOUNDARY
            "transportation", "road", "highway", "roads", "streets" -> when (kind) {
                "motorway" -> MvtStyle.Z_ROAD_MOTORWAY
                "trunk" -> MvtStyle.Z_ROAD_TRUNK
                "primary" -> MvtStyle.Z_ROAD_PRIMARY
                "secondary" -> MvtStyle.Z_ROAD_SECONDARY
                "tertiary" -> MvtStyle.Z_ROAD_TERTIARY
                "rail", "transit" -> MvtStyle.Z_RAIL
                "track" -> MvtStyle.Z_ROAD_TRACK
                "path", "footway", "pedestrian", "steps" -> MvtStyle.Z_ROAD_PATH
                else -> MvtStyle.Z_ROAD_MINOR
            }
            else -> 0
        }
    }

    override fun styleFor(
        layerName: String,
        geometryType: MvtGeometryType,
        properties: Map<String, Any?>,
    ): ShapeAttributes? {
        if (geometryType == MvtGeometryType.POINT || geometryType == MvtGeometryType.UNKNOWN) return null
        // OpenMapTiles uses `class`; Shortbread (Versatiles default) uses `kind`. Try both.
        val kind = properties["class"] ?: properties["kind"] ?: properties["subclass"]
        return when (layerName) {
            // Hydrology
            "water", "ocean", "water_polygons", "river_polygons" -> WATER_FILL
            "waterway", "water_line", "water_lines" -> WATERWAY_LINE
            // Land cover / land use
            "landcover", "land" -> when (kind) {
                "wood", "forest", "scrub", "grass", "farmland", "meadow" -> VEGETATION_FILL
                "ice", "glacier", "snow" -> SNOW_FILL
                "sand", "beach" -> SAND_FILL
                "residential", "commercial", "industrial", "neighbourhood" -> URBAN_FILL
                else -> LANDUSE_FILL
            }
            "landuse" -> when (kind) {
                "residential", "commercial", "industrial", "neighbourhood" -> URBAN_FILL
                "park", "garden", "grass", "playground", "cemetery", "recreation_ground" -> PARK_FILL
                "wood", "forest" -> VEGETATION_FILL
                else -> LANDUSE_FILL
            }
            "sites" -> when (kind) {
                "park", "garden", "playground", "recreation_ground" -> PARK_FILL
                "wood", "forest" -> VEGETATION_FILL
                else -> LANDUSE_FILL
            }
            "park", "national_park" -> PARK_FILL
            // Transport — OMT `transportation`, Shortbread `streets`. Kind values overlap.
            "transportation", "road", "highway", "roads", "transportation_name", "streets" -> when (kind) {
                "motorway", "trunk" -> ROAD_MAJOR
                "primary", "secondary", "tertiary" -> ROAD_PRIMARY
                "rail", "transit", "tram", "subway", "light_rail" -> RAIL_LINE
                else -> ROAD_MINOR
            }
            // Built environment
            "building", "buildings" -> BUILDING_FILL
            "boundary", "admin", "boundaries" -> BOUNDARY_LINE
            // Anything we don't recognise drops to a debug gray so the developer can see "I
            // got geometry but no style mapped" rather than mistaking dropped features for
            // a fetch failure. Switch [MvtVectorLayer.style] to a custom impl returning null
            // here for production tile data.
            else -> if (geometryType == MvtGeometryType.LINESTRING) UNKNOWN_LINE else UNKNOWN_FILL
        }
    }

    // Attribute constants. Constructed once and shared across every feature of their kind —
    // ShapeAttributes is value-equality, but the layer caches per-feature shapes that hold
    // refs to these instances, so reusing them is fine. Don't mutate at runtime.

    private fun fill(r: Float, g: Float, b: Float, a: Float = 1f) = ShapeAttributes().apply {
        interiorColor = Color(r, g, b, a)
        isDrawOutline = false
        shadowMode = ShadowMode.DISABLED
    }

    private fun line(r: Float, g: Float, b: Float, width: Float, a: Float = 1f) = ShapeAttributes().apply {
        isDrawInterior = false
        outlineColor = Color(r, g, b, a)
        outlineWidth = width
        shadowMode = ShadowMode.DISABLED
    }

    val WATER_FILL = fill(0.62f, 0.78f, 0.91f)
    val WATERWAY_LINE = line(0.55f, 0.72f, 0.88f, 1.2f)
    val PARK_FILL = fill(0.78f, 0.90f, 0.72f)
    val VEGETATION_FILL = fill(0.74f, 0.86f, 0.69f)
    val LANDUSE_FILL = fill(0.93f, 0.92f, 0.86f)
    val URBAN_FILL = fill(0.91f, 0.89f, 0.84f)
    val SAND_FILL = fill(0.96f, 0.93f, 0.78f)
    val SNOW_FILL = fill(0.94f, 0.97f, 1.0f)
    val ROAD_MAJOR = line(0.95f, 0.78f, 0.40f, 2.5f)
    val ROAD_PRIMARY = line(0.97f, 0.88f, 0.55f, 1.8f)
    val ROAD_MINOR = line(0.88f, 0.86f, 0.82f, 1.0f)
    val RAIL_LINE = line(0.55f, 0.55f, 0.55f, 1.0f)
    val BUILDING_FILL = fill(0.83f, 0.81f, 0.77f)
    val BOUNDARY_LINE = line(0.62f, 0.50f, 0.70f, 1.0f, a = 0.7f)
    val UNKNOWN_FILL = fill(0.85f, 0.85f, 0.85f, a = 0.5f)
    val UNKNOWN_LINE = line(0.6f, 0.6f, 0.6f, 0.8f)
}
