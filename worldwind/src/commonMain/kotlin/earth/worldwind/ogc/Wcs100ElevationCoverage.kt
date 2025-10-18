package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.geom.TileMatrixSet.Companion.fromTilePyramid
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.ogc.gml.serializersModule
import earth.worldwind.ogc.wcs.Wcs100Capabilities
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML

/**
 * Generates elevations from OGC Web Coverage Service (WCS) version 1.0.0.
 * Wcs100ElevationCoverage requires the WCS service address, coverage name, and coverage bounding sector. Get Coverage
 * requests generated for retrieving data use the WCS version 1.0.0 protocol and are limited to the EPSG:4326 coordinate
 * system. Wcs100ElevationCoverage does not perform version negotiation and assumes the service supports the format and
 * coordinate system parameters detailed here.
 */
class Wcs100ElevationCoverage private constructor(
    override val serviceAddress: String, override val coverageName: String, override val outputFormat: String,
    tileMatrixSet: TileMatrixSet, elevationSourceFactory: ElevationSourceFactory
): TiledElevationCoverage(tileMatrixSet, elevationSourceFactory), WebElevationCoverage {
    override val serviceType = SERVICE_TYPE

    constructor(
        serviceAddress: String, coverageName: String, outputFormat: String, sector: Sector, resolution: Angle
    ): this(
        serviceAddress, coverageName, outputFormat,
        fromTilePyramid(sector, if (sector.isFullSphere) 2 else 1, 1, 256, 256, resolution),
        Wcs100ElevationSourceFactory(serviceAddress, coverageName, outputFormat)
    )

    override fun clone() = Wcs100ElevationCoverage(
        serviceAddress, coverageName, outputFormat, tileMatrixSet, elevationSourceFactory
    )

    companion object {
        private const val SERVICE = "WCS"
        private const val VERSION = "1.0.0"
        const val SERVICE_TYPE = "$SERVICE $VERSION"

        private val xml = XML(serializersModule) { defaultPolicy { ignoreUnknownChildren() } }

        /**
         * Attempts to retrieve a Web Coverage Service (WCS) capabilities
         *
         * @param serviceAddress the WCS service address
         * @return WCS 1.0.0 coverage service capabilities
         */
        suspend fun getCapabilities(serviceAddress: String) = decodeWcsCapabilities(retrieveWcsCapabilities(serviceAddress))

        private suspend fun retrieveWcsCapabilities(serviceAddress: String) = DefaultHttpClient().use { httpClient ->
            val serviceUri = Uri.parse(serviceAddress).buildUpon()
                .appendQueryParameter("VERSION", VERSION)
                .appendQueryParameter("SERVICE", SERVICE)
                .appendQueryParameter("REQUEST", "GetCapabilities")
                .build()
            httpClient.get(serviceUri.toString()) { expectSuccess = true }.bodyAsText()
        }

        private suspend fun decodeWcsCapabilities(xmlText: String) = withContext(Dispatchers.Default) {
            xml.decodeFromString<Wcs100Capabilities>(xmlText)
        }
    }
}