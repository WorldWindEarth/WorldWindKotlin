package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope

class GeoTiffFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow and attaches the cross-platform [GeoTiffTutorial] —
     * synthesises a tiled GeoTIFF DEM plus a matching shaded-relief GeoTIFF, then
     * mounts them as an elevation coverage and an image layer over the same sector.
     * The synthesis runs on [lifecycleScope] off the main thread.
     */
    override fun createWorldWindow() = super.createWorldWindow().also {
        GeoTiffTutorial(it.engine, lifecycleScope).start()
    }
}
