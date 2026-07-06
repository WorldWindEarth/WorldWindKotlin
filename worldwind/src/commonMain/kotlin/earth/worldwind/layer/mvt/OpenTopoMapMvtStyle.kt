package earth.worldwind.layer.mvt

import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.Color
import earth.worldwind.shape.ShapeAttributes

/**
 * Hand-coded [MvtStyle] that approximates the palette of
 * [OpenTopoMap](https://opentopomap.org/) for MVT sources following the OpenMapTiles v3
 * schema (Versatiles, Maptiler, self-hosted OpenMapTiles). Reference implementation for the
 * imperative-DSL approach — for the same look with zoom-interpolation and labels, use the
 * rule-based [OpenTopoMapRules] instead.
 *
 * Reproduces the OpenTopoMap visual identity: cream background, muted-green landcover,
 * light-blue water, brown tracks, single-tone roads by class, gray-beige building shells.
 *
 * Not modelled here: road **casings** (the white/yellow stripe under the colored road fill).
 * WorldWind's [earth.worldwind.shape.Path] is single-colored and per-feature back-to-front
 * sub-draws don't have a stable z-order within a tile. Roads render with the fill color
 * only — close enough at typical zoom levels.
 *
 * Use as the [MvtVectorLayer.style] argument:
 * ```
 * val layer = MvtVectorLayer(
 *     source = UrlTemplateMvtTileSource("https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}"),
 *     style = OpenTopoMapMvtStyle,
 * )
 * ```
 */
object OpenTopoMapMvtStyle : MvtStyle {

    // Cream page under every fill, matching the rule-based [OpenTopoMapRules] twin.
    override val backgroundColor: Color get() = BG_CREAM

    override fun zOrderFor(
        layerName: String,
        geometryType: MvtGeometryType,
        properties: Map<String, Any?>,
    ): Int {
        val kind = properties["class"] ?: properties["kind"]
        return when (layerName) {
            "water", "ocean", "water_polygons", "river_polygons" -> MvtStyle.Z_WATER
            "waterway", "water_line", "water_lines" -> MvtStyle.Z_WATERWAY
            "landcover", "land", "natural_line", "physical_line" -> MvtStyle.Z_LANDCOVER
            "landuse" -> MvtStyle.Z_LANDUSE
            "sites" -> MvtStyle.Z_LANDUSE
            "park", "national_park" -> MvtStyle.Z_PARK
            "aeroway" -> MvtStyle.Z_AEROWAY
            "aerialways" -> MvtStyle.Z_AEROWAY
            "building", "buildings" -> MvtStyle.Z_BUILDING
            "boundary", "admin", "boundaries" -> MvtStyle.Z_BOUNDARY
            "transportation", "road", "highway", "roads", "streets" -> when (kind) {
                "motorway", "motorway_link" -> MvtStyle.Z_ROAD_MOTORWAY
                "trunk", "trunk_link" -> MvtStyle.Z_ROAD_TRUNK
                "primary", "primary_link" -> MvtStyle.Z_ROAD_PRIMARY
                "secondary", "secondary_link" -> MvtStyle.Z_ROAD_SECONDARY
                "tertiary", "tertiary_link" -> MvtStyle.Z_ROAD_TERTIARY
                "rail", "transit", "tram", "subway", "light_rail" -> MvtStyle.Z_RAIL
                "track" -> MvtStyle.Z_ROAD_TRACK
                "path", "footway", "pedestrian", "steps", "bridleway", "cycleway", "corridor" -> MvtStyle.Z_ROAD_PATH
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
        // OpenMapTiles uses `class` for the feature sub-type; Shortbread (Versatiles default)
        // uses `kind`. Read whichever is present so the same case can serve both.
        val kind = properties["class"] ?: properties["kind"] ?: properties["subclass"]
        return when (layerName) {
            // Hydrology
            //   OpenMapTiles: `water`, `ocean`, `waterway`
            //   Shortbread:   `water_polygons`, `ocean`, `river_polygons`, `water_lines`
            "water", "ocean", "water_polygons", "river_polygons" -> when (kind) {
                "swimming_pool" -> null
                else -> WATER_FILL
            }
            "waterway", "water_line", "water_lines" -> when (kind) {
                "river", "river_centerline", "canal" -> RIVER_LINE
                "stream", "drain", "ditch" -> STREAM_LINE
                else -> RIVER_LINE
            }
            // Land cover / land use
            //   OpenMapTiles: `landcover`, `landuse`, `park`, `national_park`
            //   Shortbread:   `land`, `sites`  (kind covers both forest/grass and park/cemetery)
            "landcover", "land" -> when (kind) {
                "wood", "forest" -> FOREST_FILL
                "scrub", "scrubland" -> SCRUB_FILL
                "grass", "meadow" -> GRASS_FILL
                "farmland", "farm", "farmyard" -> FARMLAND_FILL
                "wetland", "marsh" -> WETLAND_FILL
                "sand", "beach" -> SAND_FILL
                "ice", "glacier", "snow" -> SNOW_FILL
                "rock", "bare_rock", "scree" -> ROCK_FILL
                "residential", "neighbourhood" -> RESIDENTIAL_FILL
                "commercial", "retail" -> COMMERCIAL_FILL
                "industrial" -> INDUSTRIAL_FILL
                "park", "garden", "playground", "recreation_ground", "village_green" -> PARK_FILL
                "cemetery", "grave_yard" -> CEMETERY_FILL
                else -> LANDCOVER_DEFAULT_FILL
            }
            "landuse" -> when (kind) {
                "residential", "neighbourhood" -> RESIDENTIAL_FILL
                "commercial", "retail" -> COMMERCIAL_FILL
                "industrial" -> INDUSTRIAL_FILL
                "park", "garden", "playground", "recreation_ground", "village_green" -> PARK_FILL
                "wood", "forest" -> FOREST_FILL
                "cemetery", "grave_yard" -> CEMETERY_FILL
                "school", "university", "college", "hospital" -> INSTITUTION_FILL
                "farmland", "farmyard" -> FARMLAND_FILL
                "grass", "meadow" -> GRASS_FILL
                else -> LANDUSE_DEFAULT_FILL
            }
            "sites" -> when (kind) {
                "park", "garden", "playground", "recreation_ground" -> PARK_FILL
                "wood", "forest" -> FOREST_FILL
                "cemetery", "grave_yard" -> CEMETERY_FILL
                "school", "university", "college", "hospital", "library" -> INSTITUTION_FILL
                "military" -> INDUSTRIAL_FILL
                else -> LANDUSE_DEFAULT_FILL
            }
            "park", "national_park" -> PARK_FILL
            // Transport
            //   OpenMapTiles: `transportation`, `road`, `highway`, `roads`
            //   Shortbread:   `streets`  (kind matches OMT class values)
            "transportation", "road", "highway", "roads", "streets" -> when (kind) {
                "motorway" -> ROAD_MOTORWAY
                "motorway_link" -> ROAD_MOTORWAY_LINK
                "trunk" -> ROAD_TRUNK
                "trunk_link" -> ROAD_TRUNK_LINK
                "primary", "primary_link" -> ROAD_PRIMARY
                "secondary", "secondary_link" -> ROAD_SECONDARY
                "tertiary", "tertiary_link" -> ROAD_TERTIARY
                "minor", "unclassified", "residential", "living_street", "pedestrian", "road" -> ROAD_MINOR
                "service" -> ROAD_SERVICE
                "track" -> TRACK_LINE
                "path", "footway", "steps", "bridleway", "cycleway", "corridor" -> PATH_LINE
                "rail", "transit", "tram", "subway", "light_rail" -> RAIL_LINE
                "ferry" -> FERRY_LINE
                "aerialway" -> AERIALWAY_LINE
                else -> ROAD_MINOR
            }
            // Built environment
            //   OpenMapTiles: `building`
            //   Shortbread:   `buildings`
            "building", "buildings" -> BUILDING_FILL
            // Administrative boundaries — only show coarse admin levels.
            "boundary", "admin", "boundaries" -> when ((properties["admin_level"] as? Number)?.toInt() ?: 2) {
                in Int.MIN_VALUE..2 -> COUNTRY_BOUNDARY_LINE
                3, 4 -> REGION_BOUNDARY_LINE
                else -> null  // Drop municipal-level boundaries — too noisy at all but city zoom.
            }
            // Aeroways / aerialways
            "aeroway" -> if (geometryType == MvtGeometryType.POLYGON) AEROWAY_FILL else AEROWAY_LINE
            "aerialways" -> AERIALWAY_LINE
            // Cliffs/edges (rare; OMT lumps under landcover, some schemas split)
            "natural_line", "physical_line" -> CLIFF_LINE
            else -> null
        }
    }

    // ----- Color palette (mirrors OpenTopoMap Mapnik style) ---------------------------------

    private val BG_CREAM = Color(0.96f, 0.96f, 0.94f)         // page background reference
    private val WATER_BLUE = Color(0.70f, 0.85f, 0.96f)
    private val FOREST_GREEN = Color(0.75f, 0.85f, 0.69f)
    private val SCRUB_GREEN = Color(0.79f, 0.85f, 0.64f)
    private val GRASS_GREEN = Color(0.82f, 0.86f, 0.69f)
    private val PARK_GREEN = Color(0.78f, 0.86f, 0.63f)
    private val FARMLAND = Color(0.91f, 0.89f, 0.74f)
    private val WETLAND = Color(0.72f, 0.79f, 0.56f, 0.65f)
    private val SAND = Color(0.93f, 0.86f, 0.62f)
    private val SNOW = Color(0.95f, 0.97f, 0.97f)
    private val ROCK = Color(0.83f, 0.81f, 0.79f)
    private val RESIDENTIAL = Color(0.94f, 0.94f, 0.90f)
    private val COMMERCIAL = Color(0.95f, 0.91f, 0.86f)
    private val INDUSTRIAL = Color(0.92f, 0.85f, 0.83f)
    private val CEMETERY = Color(0.84f, 0.87f, 0.84f)
    private val INSTITUTION = Color(0.94f, 0.92f, 0.86f)
    private val BUILDING = Color(0.78f, 0.77f, 0.74f)
    private val LANDCOVER_DEFAULT = Color(0.91f, 0.92f, 0.86f)
    private val LANDUSE_DEFAULT = Color(0.93f, 0.92f, 0.86f)

    // Road palette: each road class has a single fill color (OpenTopoMap also paints a
    // white/yellow casing, which we skip — see KDoc).
    private val MOTORWAY_RED = Color(0.91f, 0.39f, 0.32f)
    private val TRUNK_ORANGE = Color(0.95f, 0.55f, 0.40f)
    private val PRIMARY_YELLOW = Color(0.98f, 0.84f, 0.41f)
    private val SECONDARY_YELLOW = Color(0.99f, 0.91f, 0.56f)
    private val TERTIARY_CREAM = Color(0.99f, 0.96f, 0.83f)
    private val MINOR_WHITE = Color(1.0f, 1.0f, 1.0f)
    private val SERVICE_GRAY = Color(0.92f, 0.92f, 0.90f)
    private val TRACK_BROWN = Color(0.65f, 0.45f, 0.20f)
    private val PATH_BROWN = Color(0.55f, 0.35f, 0.18f)
    private val RAIL_BLACK = Color(0.2f, 0.2f, 0.2f)
    private val FERRY_BLUE = Color(0.50f, 0.65f, 0.85f)
    private val AERIALWAY = Color(0.55f, 0.55f, 0.55f)
    private val BOUNDARY_PURPLE = Color(0.55f, 0.42f, 0.62f)
    private val CLIFF_GRAY = Color(0.5f, 0.45f, 0.4f)

    // ----- ShapeAttributes (constructed once, shared across every feature of their kind) ---

    private fun fill(c: Color) = ShapeAttributes().apply {
        interiorColor = c
        isDrawOutline = false
        shadowMode = ShadowMode.DISABLED
    }
    private fun line(c: Color, width: Float) = ShapeAttributes().apply {
        isDrawInterior = false
        outlineColor = c
        outlineWidth = width
        shadowMode = ShadowMode.DISABLED
    }

    val WATER_FILL = fill(WATER_BLUE)
    val RIVER_LINE = line(WATER_BLUE, 1.4f)
    val STREAM_LINE = line(WATER_BLUE, 0.8f)
    val FOREST_FILL = fill(FOREST_GREEN)
    val SCRUB_FILL = fill(SCRUB_GREEN)
    val GRASS_FILL = fill(GRASS_GREEN)
    val FARMLAND_FILL = fill(FARMLAND)
    val WETLAND_FILL = fill(WETLAND)
    val SAND_FILL = fill(SAND)
    val SNOW_FILL = fill(SNOW)
    val ROCK_FILL = fill(ROCK)
    val PARK_FILL = fill(PARK_GREEN)
    val RESIDENTIAL_FILL = fill(RESIDENTIAL)
    val COMMERCIAL_FILL = fill(COMMERCIAL)
    val INDUSTRIAL_FILL = fill(INDUSTRIAL)
    val CEMETERY_FILL = fill(CEMETERY)
    val INSTITUTION_FILL = fill(INSTITUTION)
    val BUILDING_FILL = fill(BUILDING)
    val LANDCOVER_DEFAULT_FILL = fill(LANDCOVER_DEFAULT)
    val LANDUSE_DEFAULT_FILL = fill(LANDUSE_DEFAULT)
    val ROAD_MOTORWAY = line(MOTORWAY_RED, 3.0f)
    val ROAD_MOTORWAY_LINK = line(MOTORWAY_RED, 2.0f)
    val ROAD_TRUNK = line(TRUNK_ORANGE, 2.6f)
    val ROAD_TRUNK_LINK = line(TRUNK_ORANGE, 1.8f)
    val ROAD_PRIMARY = line(PRIMARY_YELLOW, 2.2f)
    val ROAD_SECONDARY = line(SECONDARY_YELLOW, 1.8f)
    val ROAD_TERTIARY = line(TERTIARY_CREAM, 1.5f)
    val ROAD_MINOR = line(MINOR_WHITE, 1.2f)
    val ROAD_SERVICE = line(SERVICE_GRAY, 0.9f)
    val TRACK_LINE = line(TRACK_BROWN, 1.0f)
    val PATH_LINE = line(PATH_BROWN, 0.7f)
    val RAIL_LINE = line(RAIL_BLACK, 1.0f)
    val FERRY_LINE = line(FERRY_BLUE, 0.8f)
    val AERIALWAY_LINE = line(AERIALWAY, 0.7f)
    val COUNTRY_BOUNDARY_LINE = line(BOUNDARY_PURPLE, 1.2f)
    val REGION_BOUNDARY_LINE = line(Color(BOUNDARY_PURPLE).apply { alpha = 0.75f }, 0.9f)
    val AEROWAY_FILL = fill(Color(0.83f, 0.81f, 0.84f))
    val AEROWAY_LINE = line(Color(0.55f, 0.55f, 0.58f), 1.0f)
    val CLIFF_LINE = line(CLIFF_GRAY, 0.8f)

    /**
     * Cream page color, exposed via [backgroundColor] so the layer paints a full-tile fill under
     * every feature automatically. Also usable as the WorldWind viewport clear color when this
     * style overlays no imagery, so partial edge tiles blend into the same page tone.
     */
    val BACKGROUND_REFERENCE: Color = BG_CREAM
}
