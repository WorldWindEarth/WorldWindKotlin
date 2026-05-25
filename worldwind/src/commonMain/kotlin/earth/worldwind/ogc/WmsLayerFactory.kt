package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Sector
import earth.worldwind.layer.cache.TileSourceFactoryAdapter
import earth.worldwind.ogc.wms.WmsCapabilities
import earth.worldwind.ogc.wms.WmsLayer
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML

object WmsLayerFactory {
    private const val SERVICE = "WMS"
    private const val VERSION = "1.3.0"
    private const val DEFAULT_WMS_NUM_LEVELS = 20
    private val compatibleImageFormats = listOf("image/png", "image/jpg", "image/jpeg", "image/gif", "image/bmp")
    private val xml = XML { defaultPolicy { ignoreUnknownChildren() } }

    /**
     * Create a WMS tiled image layer (cache-agnostic).
     *
     * Capabilities resolution is offline-first: [serviceMetadata] (caller-supplied XML) is
     * used when present and it deserializes cleanly; only when it is missing, blank, or
     * undecodable does the factory issue an online `GetCapabilities` request. The cache
     * subsystem is not consulted here — see the canonical 2-step
     * `factory + ContentManager.attachCache` pattern.
     *
     * @param serviceAddress WMS service address
     * @param layerNames Layer names to combine into the resulting image layer
     * @param displayName Optional layer display name
     * @param serviceMetadata Optional capabilities XML override (skips the online fetch)
     */
    suspend fun createLayer(
        serviceAddress: String, layerNames: List<String>,
        displayName: String? = null,
        serviceMetadata: String? = null,
    ): WmsImageLayer {
        require(serviceAddress.isNotEmpty()) {
            logMessage(ERROR, "WmsLayerFactory", "createLayer", "missingServiceAddress")
        }
        require(layerNames.isNotEmpty()) {
            logMessage(ERROR, "WmsLayerFactory", "createLayer", "missingLayerNames")
        }
        val (xmlText, capabilities) = resolveCapabilities(serviceAddress, serviceMetadata)
        val layers = capabilities.getNamedLayers(layerNames)
        require(layers.isNotEmpty()) {
            makeMessage("WmsLayerFactory", "createLayer", "Provided layer names did not match available layers")
        }
        val wmsLayerConfig = getLayerConfigFromWmsCapabilities(layers)
        val imageFormat = wmsLayerConfig.imageFormat ?: "image/png"
        return createWmsImageLayer(serviceAddress, xmlText, layers, wmsLayerConfig, imageFormat, displayName)
    }

    /**
     * Attempts to retrieve a Web Map Service (WMS) capabilities
     *
     * @param serviceAddress the WMS service address
     * @return WMS 1.3.0 map service capabilities
     */
    suspend fun getCapabilities(serviceAddress: String) = decodeWmsCapabilities(getCapabilitiesXml(serviceAddress))

    /**
     * Issue a `GetCapabilities` request and return the raw XML. Exposed so cache-aware
     * helpers can short-circuit to a persisted copy without paying for a parse round-trip.
     */
    suspend fun getCapabilitiesXml(serviceAddress: String) = DefaultHttpClient().use { httpClient ->
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", VERSION)
            .appendQueryParameter("SERVICE", SERVICE)
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build()
        httpClient.get(serviceUri.toString()) { expectSuccess = true }.bodyAsText()
    }

    private suspend fun decodeWmsCapabilities(xmlText: String) = withContext(Dispatchers.Default) {
        xml.decodeFromString<WmsCapabilities>(xmlText)
    }

    /**
     * Resolve the capabilities offline-first. Use [serviceMetadata] when it is present and
     * deserializes cleanly; otherwise — missing, blank, or undecodable — issue an online
     * `GetCapabilities` request. Returns the XML actually used together with its parsed
     * form so the caller persists the exact metadata it built the layer from.
     */
    private suspend fun resolveCapabilities(
        serviceAddress: String, serviceMetadata: String?,
    ): Pair<String, WmsCapabilities> {
        serviceMetadata?.takeIf { it.isNotBlank() }?.let { stored ->
            runCatching { stored to decodeWmsCapabilities(stored) }.onFailure {
                logMessage(
                    WARN, "WmsLayerFactory", "resolveCapabilities",
                    "Stored WMS capabilities failed to deserialize (${it.message}); fetching online"
                )
            }.getOrNull()?.let { return it }
        }
        val online = getCapabilitiesXml(serviceAddress)
        return online to decodeWmsCapabilities(online)
    }

    private fun createWmsImageLayer(
        serviceAddress: String, serviceMetadata: String, wmsLayers: List<WmsLayer>,
        wmsLayerConfig: WmsLayerConfig, imageFormat: String, name: String?,
    ) = WmsImageLayer(
        serviceAddress = serviceAddress,
        serviceMetadata = serviceMetadata,
        layerName = wmsLayers.mapNotNull { lc -> lc.name }.joinToString(","),
        imageFormat = imageFormat,
        isTransparent = wmsLayerConfig.isTransparent,
        name = name ?: wmsLayers.joinToString(",") { lc -> lc.title },
        tiledSurfaceImage = createWmsSurfaceImage(wmsLayers, wmsLayerConfig, imageFormat),
    )

    private fun createWmsSurfaceImage(
        wmsLayers: List<WmsLayer>, wmsLayerConfig: WmsLayerConfig, imageFormat: String,
    ): TiledSurfaceImage {
        // Check if the server supports multiple layer request
        val layerLimit = wmsLayers[0].capability?.capabilities?.service?.layerLimit
        require (layerLimit == null || layerLimit >= wmsLayers.size) {
            makeMessage(
                "WmsLayerFactory", "createFromWmsAsync",
                "The number of layers specified exceeds the services limit"
            )
        }
        val levelSet = LevelSet(getLevelSetConfigFromWmsCapabilities(wmsLayers))
        val networkSource = WmsTileSource(wmsLayerConfig, levelSet)
        val tileFactory = TileSourceFactoryAdapter(networkSource, imageFormat)
        return TiledSurfaceImage(tileFactory, levelSet)
    }

    internal fun getLayerConfigFromWmsCapabilities(wmsLayers: List<WmsLayer>): WmsLayerConfig {
        // Construct the WmsTiledImage renderable from the WMS Capabilities properties
        val wmsCapabilities = wmsLayers[0].capability?.capabilities
        val serviceAddress = wmsCapabilities?.capability?.request?.getMap?.getUrl ?: error(
            makeMessage("WmsLayerFactory", "getLayerConfigFromWmsCapabilities", "Unable to resolve GetMap URL")
        )
        var matchingCoordinateSystems: MutableSet<String>? = null
        for (wmsLayer in wmsLayers) {
            val wmsLayerCoordinateSystems = wmsLayer.referenceSystems
            if (matchingCoordinateSystems == null) matchingCoordinateSystems = wmsLayerCoordinateSystems.toMutableSet()
            else matchingCoordinateSystems.retainAll(wmsLayerCoordinateSystems.toSet())
        }
        val wmsLayerConfig = WmsLayerConfig(serviceAddress, wmsLayers.joinToString(",") { l -> l.name ?: "" })
        when (val wmsVersion = wmsCapabilities.version) {
            "1.3.0" -> wmsLayerConfig.wmsVersion = wmsVersion
            else -> error(
                makeMessage("WmsLayerFactory", "getLayerConfigFromWmsCapabilities", "Version not compatible")
            )
        }
        if (matchingCoordinateSystems?.contains("EPSG:4326") == true) {
            wmsLayerConfig.coordinateSystem = "EPSG:4326"
        } else if (matchingCoordinateSystems?.contains("CRS:84") == true) {
            wmsLayerConfig.coordinateSystem = "CRS:84"
        } else error(
            makeMessage("WmsLayerFactory", "getLayerConfigFromWmsCapabilities", "Coordinate systems not compatible")
        )

        // Negotiate Image Formats
        val imageFormats = wmsCapabilities.capability.request.getMap.formats
        wmsLayerConfig.imageFormat = compatibleImageFormats.firstOrNull { format -> imageFormats.contains(format) } ?: error(
            makeMessage("WmsLayerFactory", "getLayerConfigFromWmsCapabilities", "Image Formats Not Compatible")
        )

        // Setup transparency. If at least one layer is opaque then transparency is disabled
        wmsLayerConfig.isTransparent = !wmsLayers.any { layer -> layer.isOpaque }

        return wmsLayerConfig
    }

    internal fun getLevelSetConfigFromWmsCapabilities(layerCapabilities: List<WmsLayer>): LevelSetConfig {
        val levelSetConfig = LevelSetConfig()
        var minScaleDenominator = Double.MAX_VALUE
        val sector = Sector()
        for (layerCapability in layerCapabilities) {
            layerCapability.minScaleDenominator?.let { minScaleDenominator = minScaleDenominator.coerceAtMost(it) }
            layerCapability.geographicBoundingBox?.let { sector.union(it) }
        }
        if (!sector.isEmpty) levelSetConfig.sector.copy(sector) else error(
            makeMessage(
                "WmsLayerFactory", "getLevelSetConfigFromWmsCapabilities", "Geographic Bounding Box Not Defined"
            )
        )
        levelSetConfig.numLevels = when {
            minScaleDenominator != Double.MAX_VALUE -> {
                // WMS 1.3.0 scale configuration. Based on the WMS 1.3.0 spec page 28. The hard coded value 0.00028 is
                // detailed in the spec as the common pixel size of 0.28mm x 0.28mm. Configures the maximum level not to
                // exceed the specified min scale denominator.
                val minMetersPerPixel = minScaleDenominator * 0.00028
                val minRadiansPerPixel = minMetersPerPixel / Ellipsoid.WGS84.semiMajorAxis
                levelSetConfig.numLevelsForMinResolution(minRadiansPerPixel)
            }
            else -> DEFAULT_WMS_NUM_LEVELS // Default scale configuration when no minimum scale denominator or scale hint is provided.
        }
        return levelSetConfig
    }

}
