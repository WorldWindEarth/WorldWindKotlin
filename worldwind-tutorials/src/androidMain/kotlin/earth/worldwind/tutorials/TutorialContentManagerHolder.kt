package earth.worldwind.tutorials

import android.content.Context
import earth.worldwind.formats.gpkg.GpkgContentManager
import java.io.File

/**
 * Process-lifetime holder for the tutorial app's shared `GpkgContentManager`. Every
 * fragment that wires a cache (Google Sat, NASADEM, WMS, WMTS, WCS, MVT, OSM Buildings,
 * Shapefile) reaches into here so they all share **one** open SQLite connection to
 * `cache_content.gpkg`.
 *
 * Per-fragment construction was the original pattern, but Android's `SQLiteOpenHelper`
 * doesn't close lingering connections promptly on fragment teardown — opening a second
 * connection while the first still holds the file lock fails with
 * `(5) statement aborts at 2: [PRAGMA journal_mode=TRUNCATE] database is locked`
 * during NGA's journal-mode setup. One shared instance avoids the race entirely.
 *
 * The instance stays open for the process lifetime; the OS reclaims it on process death.
 */
internal object TutorialContentManagerHolder {
    @Volatile private var instance: GpkgContentManager? = null

    fun get(context: Context): GpkgContentManager {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val path = File(context.applicationContext.cacheDir, "cache_content.gpkg").absolutePath
            val created = GpkgContentManager(path)
            instance = created
            return created
        }
    }
}
