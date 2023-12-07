package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Sector
import earth.worldwind.render.image.ImageSource.Companion.fromUrlString
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.Level
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.TileFactory

/**
 * Factory for constructing URLs associated with WMS Get Map requests.
 */
open class WmsTileFactory(
    /**
     * The WMS service address used to build Get Map URLs.
     */
    var serviceAddress: String,

    /**
     * The WMS protocol version.
     */
    var wmsVersion: String,

    /**
     * The comma-separated list of WMS layer names.
     */
    var layerNames: String,
    /**
     * The comma-separated list of WMS style names. May be null in which case the default style is assumed.
     */
    var styleNames: String?
): TileFactory {
    /**
     * The coordinate reference system to use in Get Map URLs. Defaults to EPSG:4326.
     */
    var coordinateSystem = "EPSG:4326"
    /**
     * The image content type to use in Get Map URLs. May be null in which case a default format is assumed.
     */
    var imageFormat: String? = null
    /**
     * Indicates whether Get Map URLs should include transparency.
     */
    var isTransparent = true
    /**
     * The time parameter to include in Get Map URLs. May be null in which case no time parameter is included.
     */
    var timeString: String? = null

    /**
     * Constructs a level set with a specified configuration. The configuration's service address, WMS protocol version,
     * layer names and coordinate reference system must be non-null. The style names may be null, in which case the
     * default style is assumed. The time string may be null, in which case no time parameter is included.
     *
     * @param config the configuration for this URL builder
     */
    constructor(config: WmsLayerConfig): this(config.serviceAddress, config.wmsVersion, config.layerNames, config.styleNames) {
        coordinateSystem = config.coordinateSystem
        imageFormat = config.imageFormat
        isTransparent = config.isTransparent
        timeString = config.timeString
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) = ImageTile(sector, level, row, column).apply {
        urlForTile(sector, level.tileWidth, level.tileHeight).let { urlString ->
            // Assign resource post-processor to transform received resource and save it in cache if necessary
            imageSource = fromUrlString(urlString).also { it.postprocessor = this }
        }
    }

    fun urlForTile(sector: Sector, width: Int, height: Int) = Uri.parse(serviceAddress).buildUpon().apply {
        require(width >= 1 && height >= 1) {
            logMessage(ERROR, "WmsTileFactory", "urlForTile", "invalidWidthOrHeight")
        }
        appendQueryParameter("VERSION", wmsVersion)
        appendQueryParameter("SERVICE", "WMS")
        appendQueryParameter("REQUEST", "GetMap")
        appendQueryParameter("LAYERS", layerNames)
        appendQueryParameter("STYLES", styleNames ?: "")
        appendQueryParameter("WIDTH", width.toString())
        appendQueryParameter("HEIGHT", height.toString())
        appendQueryParameter("FORMAT", imageFormat ?: "image/png")
        appendQueryParameter("TRANSPARENT", if (isTransparent) "TRUE" else "FALSE")
        if (wmsVersion == "1.3.0") {
            appendQueryParameter("CRS", coordinateSystem)
            appendQueryParameter("BBOX", sector.run {
                if (coordinateSystem == "CRS:84") {
                    "${minLongitude.inDegrees},${minLatitude.inDegrees},${maxLongitude.inDegrees},${maxLatitude.inDegrees}"
                } else {
                    "${minLatitude.inDegrees},${minLongitude.inDegrees},${maxLatitude.inDegrees},${maxLongitude.inDegrees}"
                }
            })
        } else {
            appendQueryParameter("SRS", coordinateSystem)
            appendQueryParameter("BBOX", sector.run {
                "${minLongitude.inDegrees},${minLatitude.inDegrees},${maxLongitude.inDegrees},${maxLatitude.inDegrees}"
            })
        }
        timeString?.let { appendQueryParameter("TIME", it) }
    }.build().toString()
}