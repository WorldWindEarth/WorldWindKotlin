package earth.worldwind.formats.shapefile

/**
 * Minimal projection (`.prj`) parser. Only the geographic-vs-projected distinction is
 * recognized — that matches WebWorldWind's implementation, which throws on projected
 * coordinate systems. Here we expose [coordinateSystem] and let callers decide.
 *
 * Constructed from the raw text of the `.prj` file (ASCII / Latin-1 in practice).
 */
class PrjFile(val text: String) {
    val coordinateSystem: CoordinateSystem

    init {
        val upper = text.trim().uppercase()
        coordinateSystem = when {
            upper.isEmpty() -> CoordinateSystem.UNKNOWN
            // PROJCS always nests GEOGCS, so check the outermost keyword first.
            PROJCS_PATTERN.containsMatchIn(upper) -> CoordinateSystem.PROJECTED
            GEOGCS_PATTERN.containsMatchIn(upper) -> CoordinateSystem.GEOGRAPHIC
            else -> CoordinateSystem.UNKNOWN
        }
    }

    val isGeographicCoordinateSystem: Boolean get() = coordinateSystem == CoordinateSystem.GEOGRAPHIC
    val isProjectedCoordinateSystem: Boolean get() = coordinateSystem == CoordinateSystem.PROJECTED
    val isKnownCoordinateSystem: Boolean get() = coordinateSystem != CoordinateSystem.UNKNOWN
    val isUnknownCoordinateSystem: Boolean get() = coordinateSystem == CoordinateSystem.UNKNOWN

    enum class CoordinateSystem { GEOGRAPHIC, PROJECTED, UNKNOWN }

    companion object {
        // OGC WKT keywords. The brackets can be `[` or `(`. We don't bother parsing the body
        // since we don't support projected coordinates anyway.
        private val GEOGCS_PATTERN = Regex("""GEOGCS\s*[\[(]""")
        private val PROJCS_PATTERN = Regex("""PROJCS\s*[\[(]""")
    }
}
