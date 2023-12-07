package earth.worldwind.ogc

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage

/**
 * Generates elevations from OGC Web Coverage Service (WCS) version 1.0.0.
 * <br></br>
 * Wcs100ElevationCoverage requires the WCS service address, coverage name, and coverage bounding sector. Get Coverage
 * requests generated for retrieving data use the WCS version 1.0.0 protocol and are limited to the EPSG:4326 coordinate
 * system. Wcs100ElevationCoverage does not perform version negotiation and assumes the service supports the format and
 * coordinate system parameters detailed here.
 */
class Wcs100ElevationCoverage(
    override val serviceAddress: String, override val coverageName: String, override val outputFormat: String,
    sector: Sector, resolution: Angle
): TiledElevationCoverage(
    TileMatrixSet.fromTilePyramid(sector, if (sector.isFullSphere) 2 else 1, 1, 256, 256, resolution),
    Wcs100ElevationSourceFactory(serviceAddress, coverageName, outputFormat)
), WebElevationCoverage {
    override val serviceType = SERVICE_TYPE

    companion object {
        const val SERVICE_TYPE = "WCS 1.0.0"
    }
}