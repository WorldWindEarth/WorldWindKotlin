package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.geom.TileMatrixSet.Companion.fromTilePyramid
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.ogc.gml.GmlRectifiedGrid
import earth.worldwind.ogc.gml.serializersModule
import earth.worldwind.ogc.wcs.Wcs201CoverageDescription
import earth.worldwind.ogc.wcs.Wcs201CoverageDescriptions
import earth.worldwind.ogc.wcs.Wcs201Capabilities
import earth.worldwind.util.Logger.makeMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import nl.adaptivity.xmlutil.serialization.XML

/**
 * Generates elevations from OGC Web Coverage Service (WCS) version 2.0.1.
 * Wcs201ElevationCoverage requires the WCS service address, coverage name, and coverage bounding sector. Get Coverage
 * requests generated for retrieving data use the WCS version 2.0.1 protocol and are limited to the "image/tiff" format
 * and the EPSG:4326 coordinate system. Wcs201ElevationCoverage does not perform version negotiation and assumes the
 * service supports the format and coordinate system parameters detailed here. The subset CRS is configured as EPSG:4326
 * and the axis labels are set as "Lat" and "Long". The scaling axis labels are set as:
 * http://www.opengis.net/def/axis/OGC/1/i and http://www.opengis.net/def/axis/OGC/1/j
 */
open class Wcs201ElevationCoverage private constructor(
    final override val serviceAddress: String,
    final override val coverageName: String,
    final override val outputFormat: String,
    tileMatrixSet: TileMatrixSet,
    elevationSourceFactory: ElevationSourceFactory,
    final override val serviceMetadata: String? = null
): TiledElevationCoverage(tileMatrixSet, elevationSourceFactory), WebElevationCoverage {
    final override val serviceType = SERVICE_TYPE

    /**
     * Constructs a Web Coverage Service (WCS) elevation coverage with specified WCS configuration values.
     *
     * @param serviceAddress the WCS service address
     * @param coverageName   the WCS coverage name
     * @param outputFormat   the WCS source data format
     * @param sector         the coverage's geographic bounding sector
     * @param resolution     the target resolution in angular value of latitude per texel
     * 90-degree tiles containing 256x256 elevation pixels
     *
     * @throws IllegalArgumentException If any argument is null or if the number of levels is less than 0
     */
    constructor(serviceAddress: String, coverageName: String, outputFormat: String, sector: Sector, resolution: Angle): this(
        serviceAddress, coverageName, outputFormat,
        fromTilePyramid(sector, if (sector.isFullSphere) 2 else 1, 1, 256, 256, resolution),
        Wcs201ElevationSourceFactory(serviceAddress, coverageName, outputFormat)
    )

    override fun clone() = Wcs201ElevationCoverage(
        serviceAddress, coverageName, outputFormat, tileMatrixSet, elevationSourceFactory, serviceMetadata
    ).also {
        it.displayName = displayName
        it.sector.copy(sector)
    }

    companion object {
        private const val SERVICE = "WCS"
        private const val VERSION = "2.0.1"
        const val SERVICE_TYPE = "$SERVICE $VERSION"

        private val xml = XML(serializersModule) { defaultPolicy { ignoreUnknownChildren() } }

        /**
         * Attempts to retrieve a Web Coverage Service (WCS) capabilities
         *
         * @param serviceAddress the WCS service address
         * @return WCS 2.0.1 elevation service capabilities
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
            xml.decodeFromString<Wcs201Capabilities>(xmlText)
        }

        /**
         * Attempts to construct a Web Coverage Service (WCS) elevation coverage with the provided service address and
         * coverage id. This constructor initiates an asynchronous request for the DescribeCoverage document and then uses
         * the information provided to determine a suitable Sector and level count. If the coverage id doesn't match the
         * available coverages or there is another error, no data will be provided and the error will be logged.
         *
         * @param serviceAddress   the WCS service address
         * @param coverageName     the WCS coverage name
         * @param outputFormat     the WCS source data format
         * @param serviceMetadata  optional WCS coverage description XML string to avoid online coverages request
         * @return WCS 2.0.1 elevation coverage
         */
        suspend fun createCoverage(
            serviceAddress: String, coverageName: String, outputFormat: String, serviceMetadata: String? = null
        ): Wcs201ElevationCoverage {
            // Fetch the DescribeCoverage document and determine the bounding box and number of levels
            val coverageDescription = if (serviceMetadata != null) decodeCoverageDescription(serviceMetadata)
            else describeCoverage(serviceAddress, coverageName).getCoverageDescription(coverageName) ?: error(
                makeMessage(
                    "Wcs201ElevationCoverage", "createCoverage",
                    "WCS coverage is undefined: $coverageName"
                )
            )
            val axisLabels = coverageDescription.boundedBy.envelope.axisLabelsList
            require(axisLabels.size >= 2) {
                makeMessage(
                    "Wcs201ElevationCoverage", "createCoverage",
                    "WCS coverage axis labels are undefined: $coverageName"
                )
            }
            val factory = Wcs201ElevationSourceFactory(serviceAddress, coverageName, outputFormat, axisLabels)
            val tileMatrixSet = tileMatrixSetFromCoverageDescription(coverageDescription)
            return Wcs201ElevationCoverage(
                serviceAddress, coverageName, outputFormat, tileMatrixSet, factory,
                serviceMetadata ?: xml.encodeToString(coverageDescription)
            )
        }

        private fun tileMatrixSetFromCoverageDescription(coverageDescription: Wcs201CoverageDescription): TileMatrixSet {
            val srsName = coverageDescription.boundedBy.envelope.srsName
            require(srsName != null && srsName.contains("4326")) {
                makeMessage(
                    "Wcs201ElevationCoverage", "tileMatrixSetFromCoverageDescription",
                    "WCS Envelope SRS is incompatible: $srsName"
                )
            }
            val lowerCorner = coverageDescription.boundedBy.envelope.lowerCorner.values
            val upperCorner = coverageDescription.boundedBy.envelope.upperCorner.values
            require(lowerCorner.size == 2 && upperCorner.size == 2) {
                makeMessage(
                    "Wcs201ElevationCoverage", "tileMatrixSetFromCoverageDescription",
                    "WCS Envelope is invalid"
                )
            }

            // Determine the number of data points in the i and j directions
            val geometry = coverageDescription.domainSet.geometry
            require(geometry is GmlRectifiedGrid) {
                makeMessage(
                    "Wcs201ElevationCoverage", "tileMatrixSetFromCoverageDescription",
                    "WCS domainSet Geometry is incompatible:$geometry"
                )
            }
            val gridLow = geometry.limits.gridEnvelope.low.values
            val gridHigh = geometry.limits.gridEnvelope.high.values
            require(gridLow.size == 2 && gridHigh.size == 2) {
                makeMessage(
                    "Wcs201ElevationCoverage", "tileMatrixSetFromCoverageDescription",
                    "WCS GridEnvelope is invalid"
                )
            }
            val boundingSector = fromDegrees(
                lowerCorner[0], lowerCorner[1],
                upperCorner[0] - lowerCorner[0],
                upperCorner[1] - lowerCorner[1]
            )
            val tileWidth = 256
            val tileHeight = 256
            val resolution = boundingSector.deltaLatitude.div(gridHigh[1] - gridLow[1])
            return fromTilePyramid(
                boundingSector, if (boundingSector.isFullSphere) 2 else 1, 1, tileWidth, tileHeight, resolution
            )
        }

        @Throws(OwsException::class)
        suspend fun describeCoverage(serviceAddress: String, coverageId: String) = DefaultHttpClient().use {
            val serviceUri = Uri.parse(serviceAddress).buildUpon()
                .appendQueryParameter("VERSION", VERSION)
                .appendQueryParameter("SERVICE", SERVICE)
                .appendQueryParameter("REQUEST", "DescribeCoverage")
                .appendQueryParameter("COVERAGEID", coverageId)
                .build()
            val response = it.get(serviceUri.toString())
            response.status to response.bodyAsText()
        }.let { (status, xmlText) ->
            withContext(Dispatchers.Default) {
                if (status == HttpStatusCode.OK) xml.decodeFromString<Wcs201CoverageDescriptions>(xmlText)
                else throw OwsException(xml.decodeFromString(xmlText))
            }
        }

        private suspend fun decodeCoverageDescription(xmlText: String) = withContext(Dispatchers.Default) {
            xml.decodeFromString<Wcs201CoverageDescription>(xmlText)
        }
    }
}