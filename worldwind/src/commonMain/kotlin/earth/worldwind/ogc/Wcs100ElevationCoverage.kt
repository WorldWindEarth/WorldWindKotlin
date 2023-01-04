package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage

/**
 * Generates elevations from OGC Web Coverage Service (WCS) version 1.0.0.
 * <br></br>
 * Wcs100ElevationCoverage requires the WCS service address, coverage name, and coverage bounding sector. Get Coverage
 * requests generated for retrieving data use the WCS version 1.0.0 protocol and are limited to the "image/tiff" format
 * and the EPSG:4326 coordinate system. Wcs100ElevationCoverage does not perform version negotiation and assumes the
 * service supports the format and coordinate system parameters detailed here.
 */
class Wcs100ElevationCoverage(
    serviceAddress: String, coverage: String, imageFormat: String, sector: Sector, numLevels: Int
): TiledElevationCoverage(
    TileMatrixSet.fromTilePyramid(sector, if (sector.isFullSphere) 2 else 1, 1, 256, 256, numLevels),
    Wcs100TileFactory(serviceAddress, coverage, imageFormat)
)