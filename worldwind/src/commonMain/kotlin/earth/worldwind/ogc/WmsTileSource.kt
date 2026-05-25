package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Sector
import earth.worldwind.layer.cache.HttpTileSource
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import io.ktor.client.HttpClientConfig
import kotlin.time.Duration.Companion.seconds

/**
 * [HttpTileSource] for OGC Web Map Service (WMS) endpoints. Converts slippy-map `(z, x, y)`
 * into the WMS GetMap BBOX URL by consulting the supplied [LevelSet] for the tile's
 * geographic extent.
 *
 * The `(z, x, y)` mapping matches [earth.worldwind.layer.cache.TileSourceFactoryAdapter]'s
 * expectations: `y` is top-down (slippy-map), [LevelSet] iteration produces renderer-side
 * rows bottom-up, so the source flips before computing the sector.
 *
 * Cache wiring is external — wrap in [earth.worldwind.layer.cache.CachedTileSource] to
 * persist bytes through a [earth.worldwind.layer.cache.TileStore].
 */
class WmsTileSource(
    val config: WmsLayerConfig,
    val levelSet: LevelSet,
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 5.seconds.inWholeMilliseconds,
    requestTimeoutMs: Long = 30.seconds.inWholeMilliseconds,
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : HttpTileSource(headers, connectTimeoutMs, requestTimeoutMs, clientConfig) {

    private val factory = WmsTileFactory(config)

    override suspend fun fetchTile(
        z: Int, x: Int, y: Int,
        previousEtag: String?, previousLastModified: String?,
    ): TileBlob? {
        val level = levelSet.level(z) ?: return null
        val rowsAtLevel = level.levelHeight / level.tileHeight
        if (y !in 0 until rowsAtLevel) return null
        if (x !in 0 until (level.levelWidth / level.tileWidth)) return null
        // Renderer-side row is bottom-up; the source contract is slippy-map top-down.
        val rendererRow = rowsAtLevel - y - 1
        val sector = computeTileSector(level, rendererRow, x)
        val url = factory.urlForTile(sector, level.tileWidth, level.tileHeight)
        return fetchTileBlob(Uri.parse(url).toString(), previousEtag, previousLastModified, "WMS")
    }

    private fun computeTileSector(
        level: Level, row: Int, col: Int,
    ): Sector {
        val origin = levelSet.tileOrigin
        val tileDelta = level.tileDelta
        val minLat = origin.minLatitude.plusDegrees(row * tileDelta.latitude.inDegrees)
        val maxLat = minLat + tileDelta.latitude
        val minLon = origin.minLongitude.plusDegrees(col * tileDelta.longitude.inDegrees)
        val maxLon = minLon + tileDelta.longitude
        return Sector(minLat, maxLat, minLon, maxLon)
    }
}
