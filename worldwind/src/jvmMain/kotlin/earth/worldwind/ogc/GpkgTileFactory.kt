package earth.worldwind.ogc

import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageSource

actual fun buildImageSource(
    geoPackage: GeoPackage, content: GpkgContent, zoomLevel: Int, column: Int, gpkgRow: Int, imageFormat: String?
): ImageSource = TODO("Not yet implemented")