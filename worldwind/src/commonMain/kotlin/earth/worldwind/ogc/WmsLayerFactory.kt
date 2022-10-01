package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.WorldWind
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Sector
import earth.worldwind.layer.Layer
import earth.worldwind.layer.RenderableLayer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler
import nl.adaptivity.xmlutil.serialization.XML

open class WmsLayerFactory(
    protected val mainScope: CoroutineScope,
) {
    protected val compatibleImageFormats = listOf("image/png", "image/jpg", "image/jpeg", "image/gif", "image/bmp")
    @OptIn(ExperimentalXmlUtilApi::class)
    protected val xml = XML {
        unknownChildHandler = UnknownChildHandler { _, _, _, _, _ -> emptyList() } // Ignore unknown properties
    }

    open fun createLayer(
        serviceAddress: String, layerNames: List<String>,
        creationFailed: ((Exception) -> Unit)? = null, creationSucceeded: ((Layer) -> Unit)? = null
    ): Layer {
        require(layerNames.isNotEmpty()) {
            logMessage(ERROR, "WmsLayerFactory", "createFromWms", "missingLayerNames")
        }
        val layer = RenderableLayer().apply { isPickEnabled = false }
        mainScope.launch {
            try {
                val wmsCapabilities = retrieveWmsCapabilities(serviceAddress)
                val layerCapabilities = layerNames.mapNotNull { layerName -> wmsCapabilities.getNamedLayer(layerName) }
                require(layerCapabilities.isNotEmpty()) {
                    makeMessage(
                        "WmsLayerFactory", "createFromWms", "Provided layers did not match available layers"
                    )
                }
                createWmsLayer(layerCapabilities, layer)
                creationSucceeded?.invoke(layer)
                WorldWind.requestRedraw()
            } catch (e: Exception) {
                creationFailed?.invoke(e)
            }
        }
        return layer
    }

    protected open suspend fun retrieveWmsCapabilities(serviceAddress: String) = DefaultHttpClient().use {
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "1.3.0")
            .appendQueryParameter("SERVICE", "WMS")
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build()
        val response = it.get(serviceUri.toString()) { expectSuccess = true }
        response.bodyAsText()
    }.let { xmlText ->
        withContext(Dispatchers.Default) { xml.decodeFromString<WmsCapabilities>(xmlText) }
    }

    protected open fun createWmsLayer(layerCapabilities: List<WmsLayer>, layer: RenderableLayer) {
        val wmsCapabilities = layerCapabilities[0].capability?.capabilities

        // Check if the server supports multiple layer request
        val layerLimit = wmsCapabilities?.service?.layerLimit
        require (layerLimit != null && layerLimit >= layerCapabilities.size) {
            makeMessage(
                "WmsLayerFactory", "createFromWmsAsync",
                "The number of layers specified exceeds the services limit"
            )
        }
        val wmsLayerConfig = getLayerConfigFromWmsCapabilities(layerCapabilities)
        val levelSetConfig = getLevelSetConfigFromWmsCapabilities(layerCapabilities)

        // Collect WMS Layer Titles to set the Layer Display Name
        layer.displayName = layerCapabilities.joinToString(",") { lc -> lc.title }
        val surfaceImage = TiledSurfaceImage(WmsTileFactory(wmsLayerConfig), LevelSet(levelSetConfig))

        // Add the tiled surface image to the layer on the main thread and notify the caller. Request redraw to ensure
        // that the image displays on all WorldWindows the layer may be attached to.
        layer.addRenderable(surfaceImage)
    }

    open fun getLayerConfigFromWmsCapabilities(wmsLayers: List<WmsLayer>): WmsLayerConfig {
        // Construct the WmsTiledImage renderable from the WMS Capabilities properties
        val wmsCapabilities = wmsLayers[0].capability?.capabilities
        val serviceAddress = wmsCapabilities?.capability?.request?.getMap?.getUrl ?: error(
            makeMessage(
                "WmsLayerFactory", "getLayerConfigFromWmsCapabilities",
                "Unable to resolve GetMap URL"
            )
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
                makeMessage(
                    "WmsLayerFactory", "getLayerConfigFromWmsCapabilities",
                    "Version not compatible"
                )
            )
        }
        if (matchingCoordinateSystems != null && matchingCoordinateSystems.contains("EPSG:4326")) {
            wmsLayerConfig.coordinateSystem = "EPSG:4326"
        } else if (matchingCoordinateSystems != null && matchingCoordinateSystems.contains("CRS:84")) {
            wmsLayerConfig.coordinateSystem = "CRS:84"
        } else error(
            makeMessage(
                "WmsLayerFactory", "getLayerConfigFromWmsCapabilities",
                "Coordinate systems not compatible"
            )
        )

        // Negotiate Image Formats
        val imageFormats = wmsCapabilities.capability.request.getMap.formats
        for (compatibleImageFormat in compatibleImageFormats)
            if (imageFormats.contains(compatibleImageFormat)) {
                wmsLayerConfig.imageFormat = compatibleImageFormat
                break
            }
        wmsLayerConfig.imageFormat ?: error(
            makeMessage(
                "WmsLayerFactory", "getLayerConfigFromWmsCapabilities",
                "Image Formats Not Compatible"
            )
        )
        return wmsLayerConfig
    }

    open fun getLevelSetConfigFromWmsCapabilities(layerCapabilities: List<WmsLayer>): LevelSetConfig {
        val levelSetConfig = LevelSetConfig()
        var minScaleDenominator = Double.MAX_VALUE
        val sector = Sector()
        for (layerCapability in layerCapabilities) {
            val layerMinScaleDenominator = layerCapability.minScaleDenominator
            if (layerMinScaleDenominator != null) minScaleDenominator = minScaleDenominator.coerceAtMost(layerMinScaleDenominator)
            val layerSector = layerCapability.geographicBoundingBox
            if (layerSector != null) sector.union(layerSector)
        }
        if (!sector.isEmpty) levelSetConfig.sector.copy(sector)
        else error(
            makeMessage(
                "WmsLayerFactory", "getLevelSetConfigFromWmsCapabilities",
                "Geographic Bounding Box Not Defined"
            )
        )
        when {
            minScaleDenominator != Double.MAX_VALUE -> {
                // WMS 1.3.0 scale configuration. Based on the WMS 1.3.0 spec page 28. The hard coded value 0.00028 is
                // detailed in the spec as the common pixel size of 0.28mm x 0.28mm. Configures the maximum level not to
                // exceed the specified min scale denominator.
                val minMetersPerPixel = minScaleDenominator * 0.00028
                val minRadiansPerPixel = minMetersPerPixel / Ellipsoid.WGS84.semiMajorAxis
                levelSetConfig.numLevels = levelSetConfig.numLevelsForMinResolution(minRadiansPerPixel)
            }
            else -> {
                // Default scale configuration when no minimum scale denominator or scale hint is provided.
                levelSetConfig.numLevels = DEFAULT_WMS_NUM_LEVELS
            }
        }
        return levelSetConfig
    }

    companion object {
        protected const val DEFAULT_WMS_NUM_LEVELS = 20
    }
}