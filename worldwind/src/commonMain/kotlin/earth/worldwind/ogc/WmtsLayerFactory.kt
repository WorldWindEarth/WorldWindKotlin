package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Location
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.ogc.wmts.WmtsCapabilities
import earth.worldwind.ogc.wmts.WmtsLayer
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
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.math.abs

object WmtsLayerFactory {

    private val compatibleImageFormats = listOf("image/png", "image/jpg", "image/jpeg", "image/gif", "image/bmp")
    private val compatibleCoordinateSystems = listOf(
        "urn:ogc:def:crs:OGC:1.3:CRS84",
        "urn:ogc:def:crs:EPSG::4326",
        "http://www.opengis.net/def/crs/OGC/1.3/CRS84"
    )
    @OptIn(ExperimentalXmlUtilApi::class)
    private val xml = XML {
        unknownChildHandler = UnknownChildHandler { _, _, _, _, _ -> emptyList() } // Ignore unknown properties
    }

    suspend fun createLayer(serviceAddress: String, layerIdentifier: String): TiledImageLayer {
        require(serviceAddress.isNotEmpty()) {
            logMessage(ERROR, "WmtsLayerFactory", "createLayer", "missingServiceAddress")
        }
        require(layerIdentifier.isNotEmpty()) {
            logMessage(ERROR, "WmtsLayerFactory", "createLayer", "missingLayerNames")
        }
        val wmtsLayer = retrieveWmtsCapabilities(serviceAddress).getLayer(layerIdentifier)
        requireNotNull(wmtsLayer) {
            makeMessage("WmtsLayerFactory", "createLayer", "The layer identifier specified was not found")
        }
        return object : TiledImageLayer(wmtsLayer.title ?: layerIdentifier) {
            init { tiledSurfaceImage = createWmtsSurfaceImage(wmtsLayer) }
        }
    }

    private suspend fun retrieveWmtsCapabilities(serviceAddress: String) = DefaultHttpClient().use { httpClient ->
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "1.0.0")
            .appendQueryParameter("SERVICE", "WMTS")
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build()
        httpClient.get(serviceUri.toString()) { expectSuccess = true }.bodyAsText()
    }.let { xmlText ->
        withContext(Dispatchers.Default) { xml.decodeFromString<WmtsCapabilities>(xmlText) }
    }

    private fun createWmtsSurfaceImage(wmtsLayer: WmtsLayer): TiledSurfaceImage {
        // Determine if there is a TileMatrixSet which matches our Coordinate System compatibility and tiling scheme
        val compatibleTileMatrixSets = determineCoordSysCompatibleTileMatrixSets(wmtsLayer)
        require(compatibleTileMatrixSets.isNotEmpty()) {
            makeMessage("WmtsLayerFactory", "createWmtsLayer", "Coordinate Systems Not Compatible")
        }

        // Search the list of coordinate system compatible tile matrix sets for compatible tiling schemes
        val compatibleTileMatrixSet = determineCompatibleTileMatrixSet(wmtsLayer.capabilities, compatibleTileMatrixSets) ?: error(
            makeMessage("WmtsLayerFactory", "createWmtsLayer", "Tile Schemes Not Compatible")
        )
        val tileFactory = createWmtsTileFactory(wmtsLayer, compatibleTileMatrixSet)
        val levelSet = createWmtsLevelSet(wmtsLayer, compatibleTileMatrixSet)
        return TiledSurfaceImage(tileFactory, levelSet)
    }

    private fun createWmtsTileFactory(wmtsLayer: WmtsLayer, compatibleTileMatrixSet: CompatibleTileMatrixSet): TileFactory {
        // First choice is a ResourceURL
        for (resourceUrl in wmtsLayer.resourceUrls) if (compatibleImageFormats.contains(resourceUrl.format)) {
            val template = resourceUrl.template
                .replace("{style}", wmtsLayer.styles[0].identifier)
                .replace("{TileMatrixSet}", compatibleTileMatrixSet.tileMatrixSetId)
            return WmtsTileFactory(template, compatibleTileMatrixSet.tileMatrices)
        }

        // Second choice is if the server supports KVP
        val baseUrl = determineKvpUrl(wmtsLayer)
        return if (baseUrl != null) {
            val imageFormat = compatibleImageFormats.firstOrNull { format -> wmtsLayer.formats.contains(format) } ?: error(
                makeMessage("WmtsLayerFactory", "getWmtsTileFactory", "Image Formats Not Compatible")
            )
            val styleIdentifier = wmtsLayer.styles[0].identifier
            val template = buildWmtsKvpTemplate(
                baseUrl, wmtsLayer.identifier, imageFormat, styleIdentifier, compatibleTileMatrixSet.tileMatrixSetId
            )
            WmtsTileFactory(template, compatibleTileMatrixSet.tileMatrices)
        } else error(makeMessage("WmtsLayerFactory", "getWmtsTileFactory", "No KVP Get Support"))
    }

    private fun createWmtsLevelSet(wmtsLayer: WmtsLayer, compatibleTileMatrixSet: CompatibleTileMatrixSet): LevelSet {
        val boundingBox = wmtsLayer.wgs84BoundingBox?.sector ?: error(
            makeMessage(
                "WmtsLayerFactory", "createWmtsLevelSet",
                "WGS84BoundingBox not defined for layer: " + wmtsLayer.identifier
            )
        )
        val tileMatrixSet = wmtsLayer.capabilities.getTileMatrixSet(compatibleTileMatrixSet.tileMatrixSetId) ?: error(
            makeMessage(
                "WmtsLayerFactory", "createWmtsLevelSet",
                "Compatible TileMatrixSet not found for: $compatibleTileMatrixSet"
            )
        )
        val imageSize = tileMatrixSet.tileMatrices[0].tileHeight
        return LevelSet(
            boundingBox, Location(NEG90, NEG180), Location(POS90, POS90),
            compatibleTileMatrixSet.tileMatrices.size, imageSize, imageSize
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
        .appendQueryParameter("TILEMATRIX", WmtsTileFactory.TILEMATRIX_TEMPLATE)
        .appendQueryParameter("TILEROW", WmtsTileFactory.TILEROW_TEMPLATE)
        .appendQueryParameter("TILECOL", WmtsTileFactory.TILECOL_TEMPLATE)
        .build().toString()

    private fun determineCoordSysCompatibleTileMatrixSets(layer: WmtsLayer) = layer.layerSupportedTileMatrixSets
        .filter { tileMatrixSet -> compatibleCoordinateSystems.contains(tileMatrixSet.supportedCrs) }
        .map { tileMatrixSet -> tileMatrixSet.identifier }

    private fun determineCompatibleTileMatrixSet(
        capabilities: WmtsCapabilities, tileMatrixSetIds: List<String>
    ): CompatibleTileMatrixSet? {
        val compatibleSet = CompatibleTileMatrixSet()

        // Iterate through each provided tile matrix set
        for (tileMatrixSetId in tileMatrixSetIds) {
            compatibleSet.tileMatrixSetId = tileMatrixSetId
            compatibleSet.tileMatrices.clear()
            val tileMatrixSet = capabilities.getTileMatrixSet(tileMatrixSetId)!!
            var previousHeight = 0
            // Walk through the associated tile matrices and check for compatibility with WWA tiling scheme
            for (tileMatrix in tileMatrixSet.tileMatrices) {
                // Aspect and symmetry check of current matrix
                if (2 * tileMatrix.matrixHeight != tileMatrix.matrixWidth) continue
                // Quad division check
                else if (tileMatrix.matrixWidth % 2 != 0 || tileMatrix.matrixHeight % 2 != 0) continue
                // Square image check
                else if (tileMatrix.tileHeight != tileMatrix.tileWidth) continue
                // Minimum row check
                else if (tileMatrix.matrixHeight < 2) continue

                // Parse top left corner values
                val topLeftCornerValue = tileMatrix.topLeftCorner.split("\\s+".toRegex())
                if (topLeftCornerValue.size != 2) continue

                // Convert Values
                val topLeftCorner = try {
                    doubleArrayOf(topLeftCornerValue[0].toDouble(), topLeftCornerValue[1].toDouble())
                } catch (e: Exception) {
                    logMessage(
                        WARN, "WmtsLayerFactory", "determineTileSchemeCompatibleTileMatrixSet",
                        "Unable to parse TopLeftCorner values"
                    )
                    continue
                }

                // Check top left corner values
                if (tileMatrixSet.supportedCrs == "urn:ogc:def:crs:OGC:1.3:CRS84"
                    || tileMatrixSet.supportedCrs == "http://www.opengis.net/def/crs/OGC/1.3/CRS84"
                ) {
                    if (abs(topLeftCorner[0] + 180) > 1e-9) continue
                    else if (abs(topLeftCorner[1] - 90) > 1e-9) continue
                } else if (tileMatrixSet.supportedCrs == "urn:ogc:def:crs:EPSG::4326") {
                    if (abs(topLeftCorner[1] + 180) > 1e-9) continue
                    else if (abs(topLeftCorner[0] - 90) > 1e-9) continue
                } else {
                    // The provided list of tile matrix set ids should adhere to either EPGS:4326 or CRS84
                    continue
                }

                // Ensure quad division behavior from previous tile matrix and add compatible tile matrix
                if (previousHeight == 0) {
                    previousHeight = tileMatrix.matrixHeight
                    compatibleSet.tileMatrices.add(tileMatrix.identifier)
                } else if (2 * previousHeight == tileMatrix.matrixHeight) {
                    previousHeight = tileMatrix.matrixHeight
                    compatibleSet.tileMatrices.add(tileMatrix.identifier)
                }
            }

            // Return the first compatible tile matrix set
            if (compatibleSet.tileMatrices.size > 2) return compatibleSet
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

    private class CompatibleTileMatrixSet {
        lateinit var tileMatrixSetId: String
        val tileMatrices = mutableListOf<String>()
    }
}