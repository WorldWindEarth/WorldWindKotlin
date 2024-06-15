package earth.worldwind.ogc

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.geom.TileMatrixSet.Companion.fromTilePyramid
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage

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
        const val SERVICE_TYPE = "WCS 1.0.0"
    }
}