package earth.worldwind.tutorials

import dev.icerock.moko.resources.AssetResource
import earth.worldwind.util.Logger
import java.io.File

/**
 * Lazy process-global staging of the bundled dragon 3D Tiles archives. The ZIP / SQLite readers open
 * a real filesystem file (no `jar:` URLs), so each moko-resource is extracted to a temp file on first
 * use, cached, and marked `deleteOnExit`. Mirrors [SharedStagedGeoPackage].
 */
object SharedStagedArchive {
    private val cache = HashMap<String, File>()

    /** Bundled dragon `.3tz` (ZIP container). */
    fun tz(): String = stage(MR.assets.dragon_3tz, ".3tz")

    /** Bundled dragon `.3dtiles` (SQLite container). */
    fun sqlite(): String = stage(MR.assets.dragon_3dtiles, ".3dtiles")

    @Synchronized
    private fun stage(asset: AssetResource, suffix: String): String {
        cache[suffix]?.takeIf { it.exists() && it.length() > 0 }?.let { return it.absolutePath }
        val src = asset.resourcesClassLoader.getResourceAsStream(asset.filePath)
            ?: error("SharedStagedArchive: bundled ${asset.filePath} not on classpath.")
        return File.createTempFile("dragon_", suffix).also { f ->
            f.deleteOnExit()
            try {
                src.use { input -> f.outputStream().use { input.copyTo(it) } }
            } catch (e: Throwable) {
                Logger.log(Logger.WARN, "SharedStagedArchive: extraction failed: ${e.message}")
                throw e
            }
            cache[suffix] = f
        }.absolutePath
    }
}
