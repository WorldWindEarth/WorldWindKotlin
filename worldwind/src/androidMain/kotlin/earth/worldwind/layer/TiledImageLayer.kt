package earth.worldwind.layer

import android.graphics.Bitmap.CompressFormat
import android.os.Build
import earth.worldwind.geom.Sector
import earth.worldwind.ogc.GpkgTileFactory
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.*

actual abstract class TiledImageLayer actual constructor(name: String): AbstractTiledImageLayer(name) {
    /**
     * Configures current tiled image layer to use GeoPackage database file as a cache provider.
     *
     * @param pathName Full path to GeoPackage database file. If not exists, it will be created.
     * @param tableName Name of content table inside GeoPackage database file.
     * @param readOnly Do not create GeoPackage database file and do not save newly downloaded tiles to it. Read existing tiles only.
     * @param format Tile image compression format
     * @param quality Tile image compression quality
     *
     * @return Cache configured successfully
     */
    @Suppress("DEPRECATION")
    @JvmOverloads
    suspend fun configureCache(
        pathName: String, tableName: String, readOnly: Boolean = false, format: CompressFormat = CompressFormat.PNG, quality: Int = 100
    ) = try {
        val isWebp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format == CompressFormat.WEBP_LOSSLESS || format == CompressFormat.WEBP_LOSSY
        } else {
            format == CompressFormat.WEBP
        }
        val content = getOrSetupTilesContent(pathName, tableName, readOnly, isWebp)
        tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(content).also {
            it.format = format
            it.quality = quality
        }
        true
    } catch (e: IllegalArgumentException) {
        logMessage(WARN, "TiledImageLayer", "configureCache", e.message!!)
        false
    } catch (e: IllegalStateException) {
        logMessage(WARN, "TiledImageLayer", "configureCache", e.message!!)
        false
    }

    /**
     * Start a new coroutine Job that downloads all imagery for a given sector and resolution,
     * without downloading imagery that is already in the cache.
     *
     * Note that the target resolution must be provided in radians of latitude per texel, which is the resolution in
     * meters divided by the globe radius.
     *
     * @param sector     the sector to download data for.
     * @param resolution the target resolution, provided in radians of latitude per texel.
     * @param cache      render resource cache to access absent resource list
     * @param scope      custom coroutine scope for better job management. Global scope by default.
     * @param onProgress an optional retrieval listener.
     *
     * @return the coroutine Job executing the retrieval or `null` if the specified sector does
     * not intersect the layer bounding sector.
     *
     * @throws IllegalStateException if tiled surface image is not initialized or cache not configured.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun makeLocal(
        sector: Sector, resolution: Double, cache: RenderResourceCache, scope: CoroutineScope = GlobalScope,
        onProgress: ((Int, Int) -> Unit)? = null
    ) = launchBulkRetrieval(scope, sector, resolution, onProgress) { imageSource, cacheSource, options ->
            // Check if tile exists in cache. If cache retrieval fail, then image source will be requested.
            // TODO If retrieved cache source is outdated, then retrieve original image source to refresh cache
            cache.imageDecoder.run { decodeImage(cacheSource, options) ?: decodeImage(imageSource, options) }?.also {
                // Un-mark cache source from absent list
                scope.launch(Dispatchers.Main) { cache.absentResourceList.unmarkResourceAbsent(cacheSource.hashCode()) }
            }
        }
}