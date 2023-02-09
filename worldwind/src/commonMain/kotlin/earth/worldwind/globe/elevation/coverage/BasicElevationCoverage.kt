package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.ogc.WmsElevationCoverage

/**
 * Displays NASA's global elevation coverage at 30m (1 arc-second) resolution and 900m resolution on the ocean floor.
 * By default, BasicElevationCoverage is configured to retrieve elevation coverage from the WMS at
 * [&amp;https://wms.worldwind.earth/elev](https://wms.worldwind.earth/elev?SERVICE=WMS&amp;REQUEST=GetCapabilities).
 */
class BasicElevationCoverage : WmsElevationCoverage(
    "https://wms.worldwind.earth/elev", "GEBCO,NASADEM", "application/bil16",
    Sector().setFullSphere(), Angle.fromSeconds(1.0)
)