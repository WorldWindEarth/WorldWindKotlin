package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders the bundled dragon **Cesium 3D Tiles Archive** in the `.3tz` (ZIP) container form.
 * The moko-resource is staged into the app cache dir on first use - the archive readers only
 * open real filesystem files - then read in place at render time, nothing extracted.
 */
open class Ogc3dTilesArchiveFragment : BasicGlobeFragment() {
    protected open val assetPath: String get() = MR.assets.dragon_3tz.originalPath
    protected open val stagedName: String get() = "dragon.3tz"
    private var tutorial: Ogc3dTilesArchiveTutorial? = null

    override fun createWorldWindow(): WorldWindow = super.createWorldWindow().also { wwd ->
        val ctx = requireContext()
        Ogc3dTilesArchiveTutorial(wwd.engine, lifecycleScope) {
            withContext(Dispatchers.IO) {
                File(ctx.cacheDir, stagedName).also { out ->
                    if (!out.exists()) ctx.assets
                        .open(assetPath)
                        .buffered()
                        .use { input -> out.outputStream().buffered().use { input.copyTo(it) } }
                }.path
            }
        }.also {
            tutorial = it
            it.start()
        }
    }

    override fun onDestroyView() {
        tutorial?.stop()
        tutorial = null
        super.onDestroyView()
    }
}

/** Same dataset in the `.3dtiles` (SQLite `media(key, content)`) container form. */
class Ogc3dTilesSqliteArchiveFragment : Ogc3dTilesArchiveFragment() {
    override val assetPath: String get() = MR.assets.dragon_3dtiles.originalPath
    override val stagedName: String get() = "dragon.3dtiles"
}
