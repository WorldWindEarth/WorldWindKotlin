package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.ogc.gml.GmlRectifiedGrid
import earth.worldwind.ogc.gml.serializersModule
import earth.worldwind.ogc.wcs.Wcs201CoverageDescription
import earth.worldwind.ogc.wcs.Wcs201CoverageDescriptions
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.XML

/**
 * Generates elevations from OGC Web Coverage Service (WCS) version 2.0.1.
 * <br></br>
 * Wcs201ElevationCoverage requires the WCS service address, coverage name, and coverage bounding sector. Get Coverage
 * requests generated for retrieving data use the WCS version 2.0.1 protocol and are limited to the "image/tiff" format
 * and the EPSG:4326 coordinate system. Wcs201ElevationCoverage does not perform version negotiation and assumes the
 * service supports the format and coordinate system parameters detailed here. The subset CRS is configured as EPSG:4326
 * and the axis labels are set as "Lat" and "Long". The scaling axis labels are set as:
 * <br></br>
 * http://www.opengis.net/def/axis/OGC/1/i and http://www.opengis.net/def/axis/OGC/1/j
 */
open class Wcs201ElevationCoverage: TiledElevationCoverage {
    @OptIn(ExperimentalXmlUtilApi::class)
    protected val xml = XML(serializersModule) { defaultPolicy { ignoreUnknownChildren() } }

    /**
     * Constructs a Web Coverage Service (WCS) elevation coverage with specified WCS configuration values.
     *
     * @param serviceAddress the WCS service address
     * @param coverage       the WCS coverage name
     * @param outputFormat   the WCS source data format
     * @param sector         the coverage's geographic bounding sector
     * @param resolution     the target resolution in angular value of latitude per texel
     * 90-degree tiles containing 256x256 elevation pixels
     *
     * @throws IllegalArgumentException If any argument is null or if the number of levels is less than 0
     */
    constructor(serviceAddress: String, coverage: String, outputFormat: String, sector: Sector, resolution: Angle): super(
        TileMatrixSet.fromTilePyramid(sector, if (sector.isFullSphere) 2 else 1, 1, 256, 256, resolution),
        Wcs201ElevationSourceFactory(serviceAddress, coverage, outputFormat)
    )

    /**
     * Attempts to construct a Web Coverage Service (WCS) elevation coverage with the provided service address and
     * coverage id. This constructor initiates an asynchronous request for the DescribeCoverage document and then uses
     * the information provided to determine a suitable Sector and level count. If the coverage id doesn't match the
     * available coverages or there is another error, no data will be provided and the error will be logged.
     *
     * @param serviceAddress the WCS service address
     * @param coverage       the WCS coverage name
     * @param outputFormat   the WCS source data format
     */
    constructor(serviceAddress: String, coverage: String, outputFormat: String) {
        mainScope.launch {
            try {
                // Fetch the DescribeCoverage document and determine the bounding box and number of levels
                val coverageDescriptions = describeCoverage(serviceAddress, coverage)
                val coverageDescription = coverageDescriptions.getCoverageDescription(coverage) ?: error(
                    makeMessage(
                        "Wcs201ElevationCoverage", "constructor",
                        "WCS coverage is undefined: $coverage"
                    )
                )
                val axisLabels = coverageDescription.boundedBy.envelope.axisLabelsList
                require(axisLabels.size >= 2) {
                    makeMessage(
                        "Wcs201ElevationCoverage", "constructor",
                        "WCS coverage axis labels are undefined: $coverage"
                    )
                }
                elevationSourceFactory = Wcs201ElevationSourceFactory(serviceAddress, coverage, outputFormat, axisLabels)
                tileMatrixSet = tileMatrixSetFromCoverageDescription(coverageDescription)
                WorldWind.requestRedraw()
            } catch (logged: Throwable) {
                logMessage(
                    ERROR, "Wcs201ElevationCoverage", "constructor",
                    "Exception initializing WCS coverage serviceAddress:$serviceAddress coverage:$coverage", logged
                )
            }
        }
    }

    protected open fun tileMatrixSetFromCoverageDescription(coverageDescription: Wcs201CoverageDescription): TileMatrixSet {
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
        val resolution = boundingSector.deltaLatitude.div((gridHigh[1] - gridLow[1]).toDouble())
        return TileMatrixSet.fromTilePyramid(
            boundingSector, if (boundingSector.isFullSphere) 2 else 1, 1, tileWidth, tileHeight, resolution
        )
    }

    @Throws(OwsException::class)
    protected open suspend fun describeCoverage(serviceAddress: String, coverageId: String) = DefaultHttpClient().use {
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "2.0.1")
            .appendQueryParameter("SERVICE", "WCS")
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
}