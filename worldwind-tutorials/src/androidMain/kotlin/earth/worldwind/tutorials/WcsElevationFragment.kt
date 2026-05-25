package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.ogc.Wcs100ElevationCoverage
import kotlinx.coroutines.launch

class WcsElevationFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        WcsElevationTutorial(it.engine, lifecycleScope, layerLoader = {
            // Network-only coverage; the attach below swaps in a cached
            // elevationSourceFactory once the GPKG coverage store opens.
            Wcs100ElevationCoverage(
                serviceAddress = WcsElevationTutorial.SERVICE_ADDRESS,
                coverageName = WcsElevationTutorial.COVERAGE_NAME,
                outputFormat = WcsElevationTutorial.OUTPUT_FORMAT,
                sector = WcsElevationTutorial.BOUNDING_SECTOR,
                resolution = WcsElevationTutorial.RESOLUTION,
            ).also { coverage ->
                lifecycleScope.launch { contentManager.attachCache(coverage, "WCS_3DEP") }
            }
        }).start()
    }
}
