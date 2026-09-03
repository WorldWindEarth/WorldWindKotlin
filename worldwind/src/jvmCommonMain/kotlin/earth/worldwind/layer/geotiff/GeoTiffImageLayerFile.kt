package earth.worldwind.layer.geotiff

import earth.worldwind.formats.geotiff.FileTiffDataSource
import java.io.File

/**
 * Open a local GeoTIFF file as an image layer. Returns `null` when the file isn't a
 * GeoTIFF the engine can read or georeference — the reason is logged. On success the layer
 * owns the file handle and releases it in [GeoTiffImageLayer.close].
 */
fun GeoTiffImageLayer.Companion.create(
    file: File, displayName: String = file.name
): GeoTiffImageLayer? {
    val source = FileTiffDataSource(file)
    return create(source, displayName) ?: run { source.close(); null }
}
