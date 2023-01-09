package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.ogc.WmsElevationCoverage

/**
 * Displays NASA's global elevation coverage at 90m (3 arc-second) resolution and 900m resolution on the ocean floor,
 * all from an OGC Web Map Service (WMS). By default, BasicElevationCoverage is configured to
 * retrieve elevation coverage from the WMS at [&amp;https://wms.worldwind.earth/elev](https://wms.worldwind.earth/elev?SERVICE=WMS&amp;REQUEST=GetCapabilities).
 */
class BasicElevationCoverage : WmsElevationCoverage(
    "https://wms.worldwind.earth/elev", "SRTM-CGIAR,GEBCO", "application/bil16", Sector().setFullSphere(), Angle.fromSeconds(3.0)
)