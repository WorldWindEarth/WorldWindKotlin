package earth.worldwind.formats.collada

import earth.worldwind.geom.Position
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ZipEntryImageRef
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Low-memory streaming load of a `.dae` from a file or stream (JVM/Android). Unlike [ColladaLoader.parse]
 * (String), this never allocates the whole document as a String: [ColladaStreamParser] reads from the
 * [Reader][java.io.Reader] (which is xmlutil's multiplatform `Reader` on these targets) so a multi-hundred-MB
 * photogrammetry `.dae` stays within a mobile heap. Caller sets [ColladaScene.imageSourceFactory] for textures.
 */
fun ColladaLoader.parseStream(file: File): ColladaScene =
    file.inputStream().buffered().use { parseStream(it) }

/** Streams from an already-open [input]; the caller owns its lifecycle. */
fun ColladaLoader.parseStream(input: InputStream): ColladaScene =
    ColladaStreamParser.parse(position, dirPath, BufferedReader(InputStreamReader(input, Charsets.UTF_8)))

/**
 * Loads a `.kmz` (zipped COLLADA) on JVM/Android directly from the archive — no extraction to disk. The
 * `.dae` is stream-parsed straight from its zip entry (low peak memory) and textures are decoded lazily
 * from their entries (downsampled to [ColladaScene.textureMaxDimension], evicted by the render resource
 * cache, re-decoded on demand). The model is anchored at the `doc.kml` `<Model>` placement (position,
 * orientation, scale), falling back to [fallbackPosition] when the KML lacks a location.
 *
 * [altitudeOffset] (metres) is added to the anchor altitude, correcting photogrammetry models whose KML
 * altitude is off relative to the terrain (interpreted per the KML [ColladaScene.altitudeMode]).
 *
 * The archive is kept open for the scene's lifetime to back lazy texture decodes; the caller MUST call
 * [ColladaScene.close] when discarding the scene to release the [ZipFile]. iOS/JS callers must read the
 * archive themselves (no in-repo zip) and use [parseStream] plus their own texture sources.
 */
fun loadColladaKmz(
    kmz: File,
    fallbackPosition: Position = Position.fromDegrees(0.0, 0.0, 0.0),
    altitudeOffset: Double = 0.0
): ColladaScene {
    val zip = ZipFile(kmz)
    try {
        val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        val kmlName = names.firstOrNull { it.endsWith(".kml", ignoreCase = true) }
        val placement = kmlName?.let { name ->
            zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { KmzArchive.parsePlacement(it.readText()) }
        }

        val kmlDir = kmlName?.parentDir() ?: ""
        val daeName = placement?.daeHref?.let { resolveZipEntry(kmlDir, it, names) }
            ?: names.firstOrNull { it.endsWith(".dae", ignoreCase = true) }
            ?: throw IllegalArgumentException("KMZ contains no .dae model: ${kmz.name}")
        val daeDir = daeName.parentDir()
        val anchor = placement?.position ?: fallbackPosition
        val position = if (altitudeOffset != 0.0)
            Position(anchor.latitude, anchor.longitude, anchor.altitude + altitudeOffset) else anchor

        // Empty dirPath: texture paths from the .dae are resolved by the factory against the .dae's folder.
        val scene = zip.getInputStream(zip.getEntry(daeName)).use { ColladaLoader(position, "").parseStream(it) }
        scene.imageSourceFactory = factory@{ path ->
            val entry = resolveZipEntry(daeDir, path, names) ?: return@factory null
            ImageSource.fromUnrecognized(ZipEntryImageRef(zip, entry))
        }
        scene.closeAction = { zip.close() }
        placement?.let {
            scene.scale = it.scale
            scene.zRotation = it.heading // best-effort: KML heading → rotation about local up; tune per model
            scene.xRotation = it.tilt
            scene.yRotation = it.roll
            scene.altitudeMode = it.altitudeMode
        }
        return scene
    } catch (e: Throwable) {
        zip.close()
        throw e
    }
}

private fun String.parentDir() = substringBeforeLast('/', "")

/** Resolves a `.dae`/texture path (relative to [baseDir] inside the archive) to an actual zip entry name. */
private fun resolveZipEntry(baseDir: String, rel: String, names: List<String>): String? {
    // COLLADA init_from values are URIs: normalize backslashes and percent-encoding (e.g. "%20").
    val clean = percentDecode(rel.removePrefix("file://").removePrefix("./").replace('\\', '/'))
    if (clean in names) return clean
    val normalized = normalizePath(if (baseDir.isEmpty()) clean else "$baseDir/$clean")
    if (normalized in names) return normalized
    // Basename fallback only when unambiguous, else we'd bind to a wrong same-named texture in another folder.
    val base = clean.substringAfterLast('/')
    return names.singleOrNull { it.substringAfterLast('/') == base }
}

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) { sb.append(hex.toChar()); i += 3 } else { sb.append(c); i++ }
    }
    return sb.toString()
}

private fun normalizePath(path: String): String {
    val parts = ArrayList<String>()
    for (seg in path.split('/')) when (seg) {
        "", "." -> {}
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
        else -> parts.add(seg)
    }
    return parts.joinToString("/")
}
