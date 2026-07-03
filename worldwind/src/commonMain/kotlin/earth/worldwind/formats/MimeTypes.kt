package earth.worldwind.formats

/**
 * Coarse file-extension → MIME type, shared by the archive readers (SLPK / 3TZ / .3dtiles). Advisory
 * only — content dispatch still routes on magic bytes. Returns `null` for an unrecognised extension so
 * each caller can apply its own default (e.g. a texture reader defaulting unknown types to JPEG).
 */
fun mimeForExtension(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "json" -> "application/json"
    "bin" -> "application/octet-stream"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "ktx2" -> "image/ktx2"
    "dds" -> "image/vnd.ms-dds"
    else -> null
}
