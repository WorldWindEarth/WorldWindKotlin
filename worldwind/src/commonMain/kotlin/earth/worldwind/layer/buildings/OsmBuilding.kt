package earth.worldwind.layer.buildings

import earth.worldwind.geom.Position

/**
 * One OSM building feature, normalized to a simple extrusion model: an outer ring
 * (and optional holes), with [height] meters above [minHeight].
 *
 * @property id           Source identifier (e.g. OSM way/relation id, prefixed with type).
 * @property outerRing    Closed outer polygon ring. The list MAY repeat the first point at the end.
 * @property innerRings   Optional inner rings (holes) in the same coordinate convention as [outerRing].
 * @property height       Top of the building, in meters above terrain.
 * @property minHeight    Base of the building, in meters above terrain (non-zero for upper-floor
 *                        extrusions like a tower built on top of a podium).
 * @property name         Optional display name from `name` tag.
 * @property tags         Original OSM tag bag for downstream styling (color, roof, etc.).
 */
data class OsmBuilding(
    val id: String,
    val outerRing: List<Position>,
    val innerRings: List<List<Position>> = emptyList(),
    val height: Double,
    val minHeight: Double = 0.0,
    val name: String? = null,
    val tags: Map<String, String> = emptyMap(),
)
