package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Sector
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.ogc.wms.WmsCapabilities
import earth.worldwind.ogc.wms.WmsLayer
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML

object WmsLayerFactory {

    const val SERVICE_TYPE = "WMS"
    private const val DEFAULT_WMS_NUM_LEVELS = 20
    private val compatibleImageFormats = listOf("image/png", "image/jpg", "image/jpeg", "image/gif", "image/bmp")
    private val xml = XML { defaultPolicy { ignoreUnknownChildren() } }

    suspend fun createLayer(serviceAddress: String, layerNames: List<String>): TiledImageLayer {
        require(serviceAddress.isNotEmpty()) {
            logMessage(ERROR, "WmsLayerFactory", "createLayer", "missingServiceAddress")
        }
        require(layerNames.isNotEmpty()) {
            logMessage(ERROR, "WmsLayerFactory", "createLayer", "missingLayerNames")
        }
        val wmsCapabilities = retrieveWmsCapabilities(serviceAddress)
        val wmsLayers = layerNames.mapNotNull { layerName -> wmsCapabilities.getNamedLayer(layerName) }
        require(wmsLayers.isNotEmpty()) {
            makeMessage("WmsLayerFactory", "createLayer", "Provided layers did not match available layers")
        }
        // Collect WMS Layer Titles to set the Layer Display Name
        val layerName = layerNames.joinToString(",")
        return object : TiledImageLayer(wmsLayers.joinToString(",") { lc -> lc.title }, createWmsSurfaceImage(wmsLayers)), WebImageLayer {
            override val serviceType = SERVICE_TYPE
            override val serviceAddress = serviceAddress
            override val layerName = layerName
            override val imageFormat get() = (tiledSurfaceImage?.tileFactory as? WmsTileFactory)?.imageFormat ?: "image/png"
            override val isTransparent get() = (tiledSurfaceImage?.tileFactory as? WmsTileFactory)?.isTransparent ?: true
        }
    }

    private suspend fun retrieveWmsCapabilities(serviceAddress: String) = DefaultHttpClient().use { httpClient ->
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "1.3.0")
            .appendQueryParameter("SERVICE", "WMS")
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build()
        runCatching { httpClient.get(serviceUri.toString()) { expectSuccess = true }.bodyAsText() }
            .getOrElse { error("Unable to open connection and read from service address") }
    }.let { xmlText ->
        withContext(Dispatchers.Default) { xml.decodeFromString<WmsCapabilities>(xmlText) }
    }

    private fun createWmsSurfaceImage(wmsLayers: List<WmsLayer>): TiledSurfaceImage {
        // Check if the server supports multiple layer request
        val layerLimit = wmsLayers[0].capability?.capabilities?.service?.layerLimit
        require (layerLimit != null && layerLimit >= wmsLayers.size) {
            makeMessage(
                "WmsLayerFactory", "createFromWmsAsync",
                "The number of layers specified exceeds the services limit"
            )
        }
        val wmsLayerConfig = getLayerConfigFromWmsCapabilities(wmsLayers)
        val levelSetConfig = getLevelSetConfigFromWmsCapabilities(wmsLayers)
        return TiledSurfaceImage(WmsTileFactory(wmsLayerConfig), LevelSet(levelSetConfig))
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