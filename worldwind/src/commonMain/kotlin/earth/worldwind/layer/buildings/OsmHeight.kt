package earth.worldwind.layer.buildings

/**
 * Resolves an OSM tag bag to a (minHeight, height) pair in meters.
 *
 * Order of preference, matching Cesium / OSM2World convention:
 * 1. Explicit `height` or `building:height` tag (with optional unit suffix `m` / `ft`).
 * 2. `building:levels` (× [METERS_PER_LEVEL]) plus optional `roof:height`.
 * 3. [defaultHeight] fallback (chosen by `building=*` value, see [defaultHeightForBuilding]).
 *
 * `min_height` / `building:min_height` are honored when present (for upper-floor extrusions
 * built on top of a podium, e.g. an antenna on a roof).
 */
object OsmHeight {
    const val METERS_PER_LEVEL = 3.0
    const val METERS_PER_FOOT = 0.3048

    fun resolve(tags: Map<String, String>, defaultHeight: Double = defaultHeightForBuilding(tags)): Pair<Double, Double> {
        val minHeight = parseLength(tags["min_height"]) ?: parseLength(tags["building:min_height"]) ?: 0.0

        val explicit = parseLength(tags["height"]) ?: parseLength(tags["building:height"])
        if (explicit != null) return minHeight to maxOf(explicit, minHeight + MIN_BUILDING_HEIGHT)

        val levels = tags["building:levels"]?.toDoubleOrNull()
        if (levels != null && levels > 0) {
            val roof = parseLength(tags["roof:height"]) ?: 0.0
            return minHeight to (minHeight + levels * METERS_PER_LEVEL + roof)
        }

        return minHeight to (minHeight + defaultHeight)
    }

    /**
     * Heuristic default heights for common `building=*` values when no explicit height/levels are
     * present. Picked to keep urban scenes legible without making every untagged shed a tower.
     */
    fun defaultHeightForBuilding(tags: Map<String, String>): Double {
        return when (tags["building"]) {
            "skyscraper" -> 120.0
            "apartments", "residential", "commercial", "office", "hotel", "hospital", "university" -> 18.0
            "church", "cathedral", "mosque", "temple", "chapel" -> 20.0
            "house", "detached", "bungalow", "terrace", "cabin" -> 6.0
            "garage", "garages", "shed", "hut", "carport", "kiosk" -> 3.0
            "industrial", "warehouse", "manufacture" -> 10.0
            else -> 6.0
        }
    }

    private const val MIN_BUILDING_HEIGHT = 1.0

    /**
     * Parse "12", "12.5", "12 m", "40'", "40ft" into meters. Returns null on non-numeric inputs.
     * Tolerant of trailing units because OSM data is famously inconsistent.
     */
    internal fun parseLength(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        // Strip any trailing letters / quote characters before parsing.
        var end = s.length
        while (end > 0 && !s[end - 1].isDigit() && s[end - 1] != '.') end--
        if (end == 0) return null
        val number = s.substring(0, end).toDoubleOrNull() ?: return null
        val unit = s.substring(end).trim().lowercase()
        return when (unit) {
            "", "m", "meter", "meters", "metre", "metres" -> number
            "ft", "feet", "foot", "'" -> number * METERS_PER_FOOT
            else -> number // unknown unit — best-effort, treat the value as meters
        }
    }
}
