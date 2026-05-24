package earth.worldwind.layer.mvt

/**
 * Identifies which vector-tile schema a decoded [MvtTile] follows by inspecting its layer
 * names. Useful for auto-selecting a compatible style — feeding an OpenMapTiles tile to a
 * Shortbread style (or vice versa) produces a mostly-empty map because almost no layer
 * names line up.
 *
 * Detection is best-effort: the schemas overlap on some generic names (`buildings`), so we
 * key off the schema-specific signature layers. A tile that matches multiple signatures
 * counts toward the schema with the largest match; ties resolve toward [Schema.UNKNOWN].
 */
object MvtSchemaDetector {

    enum class Schema {
        /** Versatiles default / Shortbread spec. */
        SHORTBREAD,
        /** OpenMapTiles v3 schema (Maptiler, Mapbox v8, self-hosted OpenMapTiles). */
        OPENMAPTILES,
        /** Schema couldn't be identified from the layer names. */
        UNKNOWN,
    }

    private val SHORTBREAD_SIGNATURES = setOf(
        "streets", "water_polygons", "water_lines", "land", "boundaries", "boundary_labels",
        "place_labels", "ocean", "sites", "street_polygons",
    )

    private val OPENMAPTILES_SIGNATURES = setOf(
        "transportation", "transportation_name", "waterway", "landcover", "landuse",
        "water_name", "mountain_peak", "park", "poi", "housenumber", "place",
    )

    /**
     * Inspect [tile]'s layer names and pick the best-matching schema. Returns
     * [Schema.UNKNOWN] when neither signature set has a meaningful hit.
     */
    fun detect(tile: MvtTile): Schema {
        if (tile.layers.isEmpty()) return Schema.UNKNOWN
        val names = tile.layers.map { it.name }.toSet()
        val shortbread = names.count { it in SHORTBREAD_SIGNATURES }
        val openMapTiles = names.count { it in OPENMAPTILES_SIGNATURES }
        return when {
            shortbread == 0 && openMapTiles == 0 -> Schema.UNKNOWN
            shortbread > openMapTiles -> Schema.SHORTBREAD
            openMapTiles > shortbread -> Schema.OPENMAPTILES
            else -> Schema.UNKNOWN
        }
    }
}
