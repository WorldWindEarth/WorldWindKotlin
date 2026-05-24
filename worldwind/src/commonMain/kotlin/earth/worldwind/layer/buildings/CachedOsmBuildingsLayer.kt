package earth.worldwind.layer.buildings

import earth.worldwind.geom.Position
import earth.worldwind.layer.CacheableFeatureLayer
import earth.worldwind.layer.FeatureCacheSourceFactory
import earth.worldwind.layer.cache.CachedFeatureRow
import earth.worldwind.layer.cache.CachedGeometry
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [OsmBuildingsLayer] variant that persists fetched tiles via [CacheableFeatureLayer] — GPKG on
 * JVM/Android, IndexedDB on JS. Cache hits short-circuit the network; misses write back. Empty-tile
 * sentinels keep ocean tiles from re-hitting Overpass on every launch.
 */
open class CachedOsmBuildingsLayer(
    source: OsmBuildingsSource = OverpassBuildingsSource(),
    tileZoom: Int = 15,
    tileRadius: Int = 4,
    maxLoadedTiles: Int = 256,
    maxConcurrentFetches: Int = 2,
    useOsmColors: Boolean = false,
    useBatchedRendering: Boolean = true,
    displayName: String? = "OSM Buildings",
) : OsmBuildingsLayer(
    source = source,
    tileZoom = tileZoom,
    tileRadius = tileRadius,
    maxLoadedTiles = maxLoadedTiles,
    maxConcurrentFetches = maxConcurrentFetches,
    useOsmColors = useOsmColors,
    useBatchedRendering = useBatchedRendering,
    displayName = displayName,
), CacheableFeatureLayer {

    override var cacheSourceFactory: FeatureCacheSourceFactory? = null

    override suspend fun loadBuildings(key: TileKey): List<OsmBuilding> {
        val cache = cacheSourceFactory ?: return source.fetchBuildings(key.sector)

        val cachedRows = cache.readFeatureTile(key.z, key.x, key.y)
        if (cachedRows.isNotEmpty()) return cachedRows.toOsmBuildings()

        val fetched = source.fetchBuildings(key.sector)
        // Best-effort write — persist failure must not break rendering.
        runCatching {
            cache.writeFeatureTile(key.z, key.x, key.y, fetched.toFeatureRows())
        }.onFailure {
            logMessage(WARN, "CachedOsmBuildingsLayer", "loadBuildings",
                "Failed to persist tile $key: ${it::class.simpleName}: ${it.message}")
        }
        return fetched
    }

    private fun List<CachedFeatureRow>.toOsmBuildings(): List<OsmBuilding> {
        val result = ArrayList<OsmBuilding>(size)
        for (row in this) {
            val polygon = row.geometry as? CachedGeometry.Polygon ?: continue
            val props = row.properties?.let { parseProperties(it) } ?: continue
            val rings = polygon.rings
            if (rings.isEmpty()) continue
            val outer = rings.first().toPositions(props.height)
            if (outer.size < 3) continue
            val inner = rings.drop(1).map { it.toPositions(props.height) }
            result += OsmBuilding(
                id = props.id,
                outerRing = outer,
                innerRings = inner,
                height = props.height,
                minHeight = props.minHeight,
                name = props.name,
                tags = props.tags ?: emptyMap(),
            )
        }
        return result
    }

    private fun List<OsmBuilding>.toFeatureRows(): List<CachedFeatureRow> = mapNotNull { b ->
        if (b.outerRing.size < 3) return@mapNotNull null
        val outer = b.outerRing.toLineString()
        val holes = b.innerRings.filter { it.size >= 3 }.map { it.toLineString() }
        val polygon = CachedGeometry.Polygon(listOf(outer) + holes)
        CachedFeatureRow(polygon, JSON.encodeToString(b.toJson()))
    }

    private fun CachedGeometry.LineString.toPositions(altitude: Double): List<Position> {
        val result = ArrayList<Position>(points.size)
        for (p in points) result += Position.fromDegrees(p.y, p.x, p.z ?: altitude)
        // GPKG rings include the closing vertex; OsmBuilding rings don't.
        if (result.size >= 2 && result.first() == result.last()) result.removeAt(result.lastIndex)
        return result
    }

    private fun List<Position>.toLineString(): CachedGeometry.LineString =
        CachedGeometry.LineString(map { CachedGeometry.Point(it.longitude.inDegrees, it.latitude.inDegrees, it.altitude) })

    private fun OsmBuilding.toJson(): BuildingJson = BuildingJson(
        id = id,
        height = height,
        minHeight = minHeight,
        name = name,
        tags = tags.takeIf { it.isNotEmpty() },
    )

    private fun parseProperties(text: String): BuildingJson? = runCatching {
        JSON.decodeFromString<BuildingJson>(text)
    }.getOrNull()

    @Serializable
    private data class BuildingJson(
        val id: String = "",
        val height: Double = 0.0,
        val minHeight: Double = 0.0,
        val name: String? = null,
        val tags: Map<String, String>? = null,
    )

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
