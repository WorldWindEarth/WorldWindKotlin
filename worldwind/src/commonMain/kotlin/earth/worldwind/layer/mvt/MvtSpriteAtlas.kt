package earth.worldwind.layer.mvt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One icon's slice within a Mapbox-format sprite sheet: pixel rectangle within the atlas
 * PNG plus optional `pixelRatio` (`@2x` / `@3x` HiDPI variants ship the same icon at integer
 * scales of the base; the renderer divides by pixelRatio when sizing the on-screen Placemark).
 */
class MvtSpriteEntry(
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixelRatio: Double = 1.0,
    /** Optional content-rect inset, used by Mapbox icons that have padded transparent halo. */
    val content: IntArray? = null,
    val sdf: Boolean = false,
) {
    override fun toString(): String =
        "MvtSpriteEntry(name=$name, x=$x, y=$y, ${width}x$height, pixelRatio=$pixelRatio)"
}

/**
 * Mapbox sprite atlas: PNG bytes + JSON manifest. The PNG packs N icons into a single image
 * and the manifest maps each icon name → its (x, y, width, height) sub-rectangle. The
 * platform-specific [iconFactory] decodes the atlas once and crops per-icon on demand.
 *
 * Standard Mapbox layout — a sprite endpoint exposes two files at the same URL stem:
 *   - `<stem>.json` (this manifest)
 *   - `<stem>.png` (the packed image)
 *
 * Construct via [MvtSpriteAtlasLoader.load] for the HTTP fetch path, or directly here when
 * you have the bytes (test fixtures, bundled assets).
 */
class MvtSpriteAtlas(
    /** Parsed manifest, keyed by icon name. */
    val entries: Map<String, MvtSpriteEntry>,
    /** Raw PNG bytes for the packed atlas image — kept so the platform crop path can decode. */
    val imageBytes: ByteArray,
) {
    fun entry(name: String): MvtSpriteEntry? = entries[name]

    /**
     * Construct an [earth.worldwind.render.image.ImageSource.ImageFactory] that, when run by
     * the engine's image-decoder pipeline, yields just this icon's cropped sub-image. Returns
     * null if [name] isn't in the manifest.
     */
    fun iconFactory(name: String): MvtAtlasIconFactory? {
        val entry = entries[name] ?: return null
        return MvtAtlasIconFactory(this, entry)
    }

    companion object {
        /** Parse a Mapbox sprite JSON manifest string into the entry map. */
        fun parseManifest(json: String): Map<String, MvtSpriteEntry> {
            val root = Json.parseToJsonElement(json) as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, MvtSpriteEntry>()
            for ((name, value) in root) {
                val obj = value as? JsonObject ?: continue
                val x = (obj["x"] as? JsonPrimitive)?.intOrNull ?: continue
                val y = (obj["y"] as? JsonPrimitive)?.intOrNull ?: continue
                val w = (obj["width"] as? JsonPrimitive)?.intOrNull ?: continue
                val h = (obj["height"] as? JsonPrimitive)?.intOrNull ?: continue
                val pr = (obj["pixelRatio"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
                val sdfBool = (obj["sdf"] as? JsonPrimitive)?.contentOrNull == "true" ||
                    (obj["sdf"] as? JsonPrimitive)?.contentOrNull == "1"
                out[name] = MvtSpriteEntry(name, x, y, w, h, pr, sdf = sdfBool)
            }
            return out
        }
    }
}
