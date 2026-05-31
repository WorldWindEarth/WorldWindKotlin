package earth.worldwind.tutorials

import earth.worldwind.util.Logger
import java.io.File

/**
 * Lazy process-global staging of the bundled tutorial GeoPackage. The NGA GeoPackage
 * driver opens a real SQLite file (no `jar:`-scheme URLs), so the moko-resource has to
 * land on disk before [GpkgContentManager] can read it.
 *
 * First call extracts the asset to a temp file; subsequent calls reuse the same cached
 * path. The file is `deleteOnExit`-marked so the JVM cleans up when the tutorial app
 * shuts down.
 */
object SharedStagedGeoPackage {

    @Volatile private var cached: File? = null

    /**
     * Returns a filesystem path to the bundled GeoPackage, extracting once on first call.
     * Throws if extraction fails — the GeoPackage tutorial is meaningless without it.
     */
    @Synchronized
    fun path(): String {
        cached?.takeIf { it.exists() && it.length() > 0 }?.let { return it.absolutePath }
        val gpkg = MR.assets.geopackage_tutorial_gpkg
        val src = gpkg.resourcesClassLoader.getResourceAsStream(gpkg.filePath)
            ?: error("SharedStagedGeoPackage: bundled ${gpkg.filePath} not on classpath.")
        return File.createTempFile("geopackage_tutorial_", ".gpkg").also { f ->
            f.deleteOnExit()
            try {
                src.use { input -> f.outputStream().use { input.copyTo(it) } }
            } catch (e: Throwable) {
                Logger.log(Logger.WARN, "SharedStagedGeoPackage: extraction failed: ${e.message}")
                throw e
            }
            cached = f
        }.absolutePath
    }
}
