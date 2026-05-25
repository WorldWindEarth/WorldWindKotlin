package earth.worldwind.layer.buildings

import earth.worldwind.geom.Position
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared (geometry + properties) ↔ [OsmBuilding] codec. Used by every [OverpassBuildingsSource]
 * (and any other [earth.worldwind.layer.source.TiledFeatureSource] producing OSM buildings) when
 * emitting cache rows, and by [OsmBuildingsLayer] when decoding rows the cache replays.
 */
internal object OsmBuildingCodec {
    private val JSON = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Encode [b] as a [CachedFeatureRow] — outer ring + holes as [CachedGeometry.Polygon],
     *  scalar fields + tags as a JSON object string. Returns `null` for degenerate rings. */
    fun encode(b: OsmBuilding): CachedFeatureRow? {
        if (b.outerRing.size < 3) return null
        val outer = b.outerRing.toLineString()
        val holes = b.innerRings.filter { it.size >= 3 }.map { it.toLineString() }
        val polygon = CachedGeometry.Polygon(listOf(outer) + holes)
        val payload = BuildingJson(
            id = b.id,
            height = b.height,
            minHeight = b.minHeight,
            name = b.name,
            tags = b.tags.takeIf { it.isNotEmpty() },
        )
        return CachedFeatureRow(polygon, JSON.encodeToString(payload))
    }

    /** Decode a [CachedFeatureRow] produced by [encode] back into an [OsmBuilding]. Returns
     *  `null` for malformed rows (wrong geometry kind, undecodable properties, etc.). */
    fun decode(row: CachedFeatureRow): OsmBuilding? {
        val polygon = row.geometry as? CachedGeometry.Polygon ?: return null
        val props = row.properties?.let { runCatching { JSON.decodeFromString<BuildingJson>(it) }.getOrNull() }
            ?: return null
        val rings = polygon.rings
        if (rings.isEmpty()) return null
        val outer = rings.first().toPositions(props.height)
        if (outer.size < 3) return null
        val inner = rings.drop(1).map { it.toPositions(props.height) }
        return OsmBuilding(
            id = props.id,
            outerRing = outer,
            innerRings = inner,
            height = props.height,
            minHeight = props.minHeight,
            name = props.name,
            tags = props.tags ?: emptyMap(),
        )
    }

    private fun CachedGeometry.LineString.toPositions(altitude: Double): List<Position> {
        val result = ArrayList<Position>(points.size)
        for (p in points) result += Position.fromDegrees(p.y, p.x, p.z ?: altitude)
        // GPKG rings include the closing vertex; OsmBuilding rings don't.
        if (result.size >= 2 && result.first() == result.last()) result.removeAt(result.lastIndex)
        return result
    }

    private fun List<Position>.toLineString(): CachedGeometry.LineString = CachedGeometry.LineString(
        map { CachedGeometry.Point(it.longitude.inDegrees, it.latitude.inDegrees, it.altitude) }
    )

    @Serializable
    private data class BuildingJson(
        val id: String = "",
        val height: Double = 0.0,
        val minHeight: Double = 0.0,
        val name: String? = null,
        val tags: Map<String, String>? = null,
    )
}
