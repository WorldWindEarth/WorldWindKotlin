package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.ogc.WmtsTileFactory.Companion.TILE_COL_TEMPLATE
import earth.worldwind.ogc.WmtsTileFactory.Companion.TILE_MATRIX_TEMPLATE
import earth.worldwind.ogc.WmtsTileFactory.Companion.TILE_ROW_TEMPLATE
import earth.worldwind.ogc.wmts.WmtsCapabilities
import earth.worldwind.ogc.wmts.WmtsLayer
import earth.worldwind.ogc.wmts.WmtsTileMatrixSet
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import earth.worldwind.util.TileFactory
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML

object WmtsLayerFactory {

    const val SERVICE_TYPE = "WMTS"
    private const val PIXEL_SIZE = 0.28E-3 // Standardized rendering pixel size (0.28mm)
    private const val STYLE_TEMPLATE = "{style}"
    private const val TILE_MATRIX_SET_TEMPLATE = "{TileMatrixSet}"
    private val compatibleImageFormats = listOf("image/png", "image/jpg", "image/jpeg", "image/gif", "image/bmp")
    private val xml = XML { defaultPolicy { ignoreUnknownChildren() } }

    /**
     * Create tiled image layer based on WMTS layer metadata retrieved from server capabilities or decoded from parameter
     *
     * @param serviceAddress WMTS service address
     * @param layerName Optional WMTS layer name to be requested into the resulting image layer
     * @param serviceMetadata Optional WMTS capabilities XML string to avoid online capabilities request
     * @param displayName Optional layer display name
     */
    suspend fun createLayer(
        serviceAddress: String, layerName: String, serviceMetadata: String? = null, displayName: String? = null
    ): TiledImageLayer {
		require(serviceAddress.isNotEmpty()) {
            logMessage(ERROR, "WmtsLayerFactory", "createLayer", "missingServiceAddress")
        }
        require(layerName.isNotEmpty()) {
            logMessage(ERROR, "WmtsLayerFactory", "createLayer", "missingLayerNames")
        }
        val wmtsCapabilitiesText = serviceMetadata ?: retrieveWmtsCapabilities(serviceAddress)
        val wmtsCapabilities = decodeWmtsCapabilities(wmtsCapabilitiesText)
        val wmtsLayer = wmtsCapabilities.getLayer(layerName)
        requireNotNull(wmtsLayer) {
            makeMessage("WmtsLayerFactory", "createLayer", "Specified layer name was not found")
        }
        return createWmtsImageLayer(serviceAddress, wmtsCapabilitiesText, wmtsLayer, displayName)
    }

    private suspend fun retrieveWmtsCapabilities(serviceAddress: String) = DefaultHttpClient().use { httpClient ->
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "1.0.0")
            .appendQueryParameter("SERVICE", "WMTS")
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build()
        httpClient.get(serviceUri.toString()) { expectSuccess = true }.bodyAsText()
    }

    private suspend fun decodeWmtsCapabilities(xmlText: String) = withContext(Dispatchers.Default) {
        xml.decodeFromString<WmtsCapabilities>(xmlText)
    }

    private fun createWmtsImageLayer(
        serviceAddress: String, serviceMetadata: String, wmtsLayer: WmtsLayer, name: String?
    ): TiledImageLayer = object : TiledImageLayer(name ?: wmtsLayer.title, createWmtsSurfaceImage(wmtsLayer)), WebImageLayer {
        override val serviceType = SERVICE_TYPE
        override val serviceAddress = serviceAddress
        override val serviceMetadata = serviceMetadata
        override val layerName = wmtsLayer.identifier
        override val imageFormat get() = (tiledSurfaceImage?.tileFactory as? WmtsTileFactory)?.imageFormat ?: "image/png"
        override val isTransparent = true // WMTS has no transparency data available
    }

    private fun createWmtsSurfaceImage(wmtsLayer: WmtsLayer): TiledSurfaceImage {
        // Search the list of coordinate system compatible tile matrix sets for compatible tiling schemes
        val tileMatrixSet = determineCompatibleTileMatrixSet(wmtsLayer.layerSupportedTileMatrixSets) ?: error(
            makeMessage("WmtsLayerFactory", "createWmtsLayer", "Tile Schemes Not Compatible")
        )
        val tileFactory = createWmtsTileFactory(wmtsLayer, tileMatrixSet)
        val levelSet = createWmtsLevelSet(wmtsLayer, tileMatrixSet)
        return TiledSurfaceImage(tileFactory, levelSet)
    }

    private fun createWmtsTileFactory(wmtsLayer: WmtsLayer, tileMatrixSet: TileMatrixSet): TileFactory {
        // First choice is a ResourceURL
        for (resourceUrl in wmtsLayer.resourceUrls) if (compatibleImageFormats.contains(resourceUrl.format)) {
            val template = resourceUrl.template
                .replace(STYLE_TEMPLATE, wmtsLayer.styles[0].identifier)
                .replace(TILE_MATRIX_SET_TEMPLATE, tileMatrixSet.identifier)
            return WmtsTileFactory(template, tileMatrixSet.tileMatrices, tileMatrixSet.matrixHeight, resourceUrl.format)
        }

        // Second choice is if the server supports KVP
        val baseUrl = determineKvpUrl(wmtsLayer)
        return if (baseUrl != null) {
            val imageFormat = compatibleImageFormats.firstOrNull { format -> wmtsLayer.formats.contains(format) } ?: error(
                makeMessage("WmtsLayerFactory", "getWmtsTileFactory", "Image Formats Not Compatible")
            )
            val styleIdentifier = wmtsLayer.styles[0].identifier
            val template = buildWmtsKvpTemplate(
                baseUrl, wmtsLayer.identifier, imageFormat, styleIdentifier, tileMatrixSet.identifier
            )
            WmtsTileFactory(template, tileMatrixSet.tileMatrices, tileMatrixSet.matrixHeight, imageFormat)
        } else error(makeMessage("WmtsLayerFactory", "getWmtsTileFactory", "No KVP Get Support"))
    }

    private fun createWmtsLevelSet(wmtsLayer: WmtsLayer, tileMatrixSet: TileMatrixSet) = with(tileMatrixSet) {
        val sector = wmtsLayer.wgs84BoundingBox?.sector ?: error(
            makeMessage(
                "WmtsLayerFactory", "createWmtsLevelSet",
                "WGS84BoundingBox not defined for layer: " + wmtsLayer.identifier
            )
        )
        val pixelSpan = scaleDenominator / toRadians(Ellipsoid.WGS84.semiMajorAxis) * PIXEL_SIZE
        val tileSpanX = tileWidth * pixelSpan
        val tileSpanY = tileHeight * pixelSpan
        val maxX = minX + tileSpanX * matrixWidth
        val minY = maxY - tileSpanY * matrixHeight
        val deltaX = maxX - minX
        val deltaY = maxY - minY
        LevelSet(
            sector, fromDegrees(minY, minX, deltaY, deltaX),
            fromDegrees(deltaY / matrixHeight, deltaX / matrixWidth),
            tileMatrices.size, tileWidth, tileHeight
        )
    }

    private fun buildWmtsKvpTemplate(
        kvpServiceAddress: String, layer: String, format: String, styleIdentifier: String, tileMatrixSet: String
    ) = Uri.parse(kvpServiceAddress).buildUpon()
        .appendQueryParameter("VERSION", "1.0.0")
        .appendQueryParameter("SERVICE", "WMTS")
        .appendQueryParameter("REQUEST", "GetTile")
        .appendQueryParameter("LAYER", layer)
        .appendQueryParameter("STYLE", styleIdentifier)
        .appendQueryParameter("FORMAT", format)
        .appendQueryParameter("TILEMATRIXSET", tileMatrixSet)
        .build().toString() + "&TILEMATRIX=$TILE_MATRIX_TEMPLATE&TILEROW=$TILE_ROW_TEMPLATE&TILECOL=$TILE_COL_TEMPLATE"

    private fun determineCompatibleTileMatrixSet(tileMatrixSets: List<WmtsTileMatrixSet>): TileMatrixSet? {
        // Iterate through each provided tile matrix set
        for (tileMatrixSet in tileMatrixSets) {
            // Determine if there is a TileMatrixSet that matches our Coordinate System compatibility and tiling scheme
            val directAxisOrder = when (tileMatrixSet.supportedCrs) {
                "urn:ogc:def:crs:OGC:1.3:CRS84", "http://www.opengis.net/def/crs/OGC/1.3/CRS84" -> true
                "urn:ogc:def:crs:EPSG::4326" -> false
                else -> continue // The provided tile matrix set should adhere to either EPGS:4326 or CRS84
            }
            val tileMatrices = mutableListOf<String>()
            var minX = 0.0
            var maxY = 0.0
            var scaleDenominator = 0.0
            var tileWidth = 0
            var tileHeight = 0
            var matrixWidth = 0
            var matrixHeight = 0
            var previousHeight = 0
            var previousWidth = 0
            var previousCorner: String? = null
            // Walk through the associated tile matrices and check for compatibility with WWA tiling scheme
            for (tileMatrix in tileMatrixSet.tileMatrices) {
                // Check and parse top left corner values
                if (previousCorner != null && tileMatrix.topLeftCorner != previousCorner) continue
                val topLeftCorner = tileMatrix.topLeftCorner.split("\\s+".toRegex())
                if (topLeftCorner.size != 2) continue

                // Check tile size equals for all tile matrices
                if (tileWidth != 0 && tileWidth != tileMatrix.tileWidth) continue
                if (tileHeight != 0 && tileHeight != tileMatrix.tileHeight) continue

                // Ensure quad division behavior from previous tile matrix
                if (previousWidth != 0 && 2 * previousWidth != tileMatrix.matrixWidth) break
                if (previousHeight != 0 && 2 * previousHeight != tileMatrix.matrixHeight) break

                // Remember the first level parameters
                if (tileMatrices.isEmpty()) {
                    try {
                        if (directAxisOrder) {
                            minX = topLeftCorner[0].toDouble()
                            maxY = topLeftCorner[1].toDouble()
                        } else {
                            minX = topLeftCorner[1].toDouble()
                            maxY = topLeftCorner[0].toDouble()
                        }
                    } catch (e: NumberFormatException) {
                        logMessage(
                            WARN, "WmtsLayerFactory", "determineTileSchemeCompatibleTileMatrixSet",
                            "Unable to parse TopLeftCorner values"
                        )
                        continue
                    }
                    scaleDenominator = tileMatrix.scaleDenominator
                    tileWidth = tileMatrix.tileWidth
                    tileHeight = tileMatrix.tileWidth
                    matrixWidth = tileMatrix.matrixWidth
                    matrixHeight = tileMatrix.matrixHeight
                }

                // And add compatible tile matrix
                tileMatrices.add(tileMatrix.identifier)
                previousHeight = tileMatrix.matrixHeight
                previousWidth = tileMatrix.matrixWidth
                previousCorner = tileMatrix.topLeftCorner
            }

            // Return the first compatible tile matrix set
            if (tileMatrices.isNotEmpty()) return TileMatrixSet(
                tileMatrixSet.identifier, tileMatrices, scaleDenominator, minX, maxY,
                tileWidth, tileHeight, matrixWidth, matrixHeight
            )
        }
        return null
    }

    /**
     * Conducts a simple search through the [WmtsLayer]s distributed computing platform resources for a URL which
     * supports KVP queries to the WMTS. This method only looks at the first entry of every array of the layers 'GET'
     * retrieval methods.
     *
     * @param layer the [WmtsLayer] to search for KVP support
     *
     * @return the URL for the supported KVP or null if KVP or 'GET' method isn't provided by the layer
     */
    private fun determineKvpUrl(layer: WmtsLayer): String? {
        val capabilities = layer.capabilities
        val operationsMetadata = capabilities.operationsMetadata ?: return null
        val getTileOperation = operationsMetadata.getTile ?: return null
        val dcp = getTileOperation.dcps
        if (dcp.isEmpty()) return null
        val getMethods = dcp[0].getMethods
        if (getMethods.isEmpty()) return null
        val constraints = getMethods[0].constraints
        if (constraints.isEmpty()) return null
        val allowedValues = constraints[0].allowedValues
        return if (allowedValues.contains("KVP")) getMethods[0].url else null
    }

    private class TileMatrixSet(
        val identifier: String,
        val tileMatrices: List<String>,
        val scaleDenominator: Double,
        val minX: Double,
        val maxY: Double,
        val tileWidth: Int,
        val tileHeight: Int,
        val matrixWidth: Int,
        val matrixHeight: Int,
    )
}