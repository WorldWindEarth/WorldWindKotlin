package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.ogc.Wcs100ElevationCoverage

class WcsElevationFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        WcsElevationTutorial(it.engine, lifecycleScope, layerLoader = {
            // Await attachCache so the coverage is added already-cached and the terrain                                                                                                        
            // loads once (instead of network-loading first, then reloading after the swap).  
            Wcs100ElevationCoverage(
                serviceAddress = WcsElevationTutorial.SERVICE_ADDRESS,
                coverageName = WcsElevationTutorial.COVERAGE_NAME,
                outputFormat = WcsElevationTutorial.OUTPUT_FORMAT,
                sector = WcsElevationTutorial.BOUNDING_SECTOR,
                resolution = WcsElevationTutorial.RESOLUTION,
            ).also { coverage ->
                contentManager.attachCache(coverage, "WCS_3DEP")
            }
        }).start()
    }
}
