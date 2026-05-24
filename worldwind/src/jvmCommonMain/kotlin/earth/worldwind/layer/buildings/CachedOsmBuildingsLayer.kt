package earth.worldwind.layer.buildings

import earth.worldwind.geom.Position
import earth.worldwind.layer.CacheableFeatureLayer
import earth.worldwind.layer.FeatureCacheSourceFactory
import earth.worldwind.ogc.GpkgFeatureCacheFactory
import earth.worldwind.ogc.gpkg.GpkgFeatureRow
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mil.nga.sf.LineString
import mil.nga.sf.Point
import mil.nga.sf.Polygon

/**
 * JVM/Android [OsmBuildingsLayer] variant that persists fetched tiles in a GeoPackage via the
 * standard [CacheableFeatureLayer] flow. Cache hits short-circuit the network; misses write back.
 * Empty-tile sentinels keep ocean tiles from re-hitting Overpass on every launch.
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
        val cache = cacheSourceFactory as? GpkgFeatureCacheFactory
            ?: return source.fetchBuildings(key.sector)

        val cachedRows = cache.geoPackage.readFeatureTile(cache.content, key.z, key.x, key.y)
        if (cachedRows.isNotEmpty()) return cachedRows.toOsmBuildings()

        val fetched = source.fetchBuildings(key.sector)
        // Best-effort write — persist failure must not break rendering.
        runCatching {
            cache.geoPackage.writeFeatureTile(
                cache.content, key.z, key.x, key.y, fetched.toFeatureRows(),
            )
        }.onFailure {
            logMessage(WARN, "CachedOsmBuildingsLayer", "loadBuildings",
                "Failed to persist tile $key: ${it::class.simpleName}: ${it.message}")
        }
        return fetched
    }

    private fun List<GpkgFeatureRow>.toOsmBuildings(): List<OsmBuilding> {
        val result = ArrayList<OsmBuilding>(size)
        for (row in this) {
            val polygon = row.geometry as? Polygon ?: continue
            val props = row.properties?.let { parseProperties(it) } ?: continue
            val outer = polygon.rings?.firstOrNull()?.toPositionList(props.height) ?: continue
            if (outer.size < 3) continue
            val inner = polygon.rings.drop(1).map { it.toPositionList(props.height) }
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

    private fun List<OsmBuilding>.toFeatureRows(): List<GpkgFeatureRow> = mapNotNull { building ->
        if (building.outerRing.size < 3) return@mapNotNull null
        val polygon = Polygon(false, false).also { p ->
            p.addRing(building.outerRing.toClosedLineString())
            for (hole in building.innerRings) if (hole.size >= 3) p.addRing(hole.toClosedLineString())
        }
        GpkgFeatureRow(polygon, JSON.encodeToString(building.toJson()))
    }

    private fun LineString.toPositionList(altitude: Double): List<Position> {
        val pts = points ?: return emptyList()
        // GPKG rings include the closing vertex; OsmBuilding rings don't.
        val result = ArrayList<Position>(pts.size)
        for (p in pts) result += Position.fromDegrees(p.y, p.x, altitude)
        if (result.size >= 2 && result.first() == result.last()) result.removeAt(result.lastIndex)
        return result
    }

    private fun List<Position>.toClosedLineString(): LineString {
        val ls = LineString(false, false)
        for (p in this) ls.addPoint(Point(p.longitude.inDegrees, p.latitude.inDegrees))
        if (first() != last()) {
            val first = first()
            ls.addPoint(Point(first.longitude.inDegrees, first.latitude.inDegrees))
        }
        return ls
    }

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
