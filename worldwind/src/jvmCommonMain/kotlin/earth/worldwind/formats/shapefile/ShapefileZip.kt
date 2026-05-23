package earth.worldwind.formats.shapefile

import earth.worldwind.layer.RenderableLayer
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Loads a shapefile distributed as a ZIP archive (the canonical packaging on most data
 * portals). The first `.shp`, `.dbf`, `.prj`, and `.cpg` entries found are routed
 * through [ShapefileLayerFactory.createLayer]. Entries deeper in subdirectories are
 * accepted too, but only the first match of each extension is used — ZIP archives
 * occasionally bundle multiple shapefiles together, and picking one deterministically
 * is better than guessing.
 *
 * JVM and Android only. iOS and JS callers need to extract the archive on their side
 * (`Compression.framework` and `DecompressionStream` respectively) and call
 * [ShapefileLayerFactory.createLayer] with the resulting bytes directly.
 */
suspend fun ShapefileLayerFactory.createLayerFromZip(
    zipBytes: ByteArray,
    displayName: String? = null,
    shapeConfiguration: (ShapefileRecord) -> ShapefileShapeConfiguration? = { ShapefileShapeConfiguration() },
): RenderableLayer {
    val bundle = extractShapefileBundle(zipBytes)
    val shp = bundle.shp ?: error("No .shp file found in the ZIP archive")
    return createLayer(
        shpBytes = shp,
        dbfBytes = bundle.dbf,
        prjBytes = bundle.prj,
        cpgBytes = bundle.cpg,
        displayName = displayName,
        shapeConfiguration = shapeConfiguration,
    )
}

/** Entries found in a shapefile ZIP bundle. All slots are nullable so we can extract
 *  partial bundles (e.g. shp-only) without failing. */
internal data class ShapefileZipBundle(
    val shp: ByteArray?,
    val dbf: ByteArray?,
    val prj: ByteArray?,
    val cpg: ByteArray?,
)

internal fun extractShapefileBundle(zipBytes: ByteArray): ShapefileZipBundle {
    var shp: ByteArray? = null
    var dbf: ByteArray? = null
    var prj: ByteArray? = null
    var cpg: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name.substringAfterLast('/').substringAfterLast('\\').lowercase()
            when {
                name.endsWith(".shp") && shp == null -> shp = zis.readBytes()
                name.endsWith(".dbf") && dbf == null -> dbf = zis.readBytes()
                name.endsWith(".prj") && prj == null -> prj = zis.readBytes()
                name.endsWith(".cpg") && cpg == null -> cpg = zis.readBytes()
            }
        }
    }
    return ShapefileZipBundle(shp, dbf, prj, cpg)
}
