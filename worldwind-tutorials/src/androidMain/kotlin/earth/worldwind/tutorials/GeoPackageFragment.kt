package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GeoPackageFragment: BasicGlobeFragment() {
    override fun createWorldWindow(): WorldWindow = super.createWorldWindow().also { wwd ->
        GeoPackageTutorial(wwd.engine, lifecycleScope) {
            // Stage the bundled GeoPackage moko-resource into the Android cache dir; the
            // GeoPackage SQLite driver only works on filesystem-backed files.
            val ctx = requireContext()
            withContext(Dispatchers.IO) {
                File(ctx.cacheDir, "geopackage_tutorial.gpkg").also { out ->
                    if (!out.exists()) ctx.assets
                        .open(MR.assets.geopackage_tutorial_gpkg.originalPath)
                        .buffered()
                        .use { input -> out.outputStream().buffered().use { input.copyTo(it) } }
                }.path
            }
        }.start()
    }
}
