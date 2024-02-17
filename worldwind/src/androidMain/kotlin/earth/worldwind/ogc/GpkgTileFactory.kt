package earth.worldwind.ogc

import android.graphics.Bitmap
import android.os.Build
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageSource

/**
 * Configure the tile with a bitmap factory that reads directly from the GeoPackage.
 */
actual fun buildImageSource(
    geoPackage: GeoPackage, content: GpkgContent, zoomLevel: Int, column: Int, gpkgRow: Int, imageFormat: String?
): ImageSource {
    val format = when {
        imageFormat.equals("image/jpeg", true) -> Bitmap.CompressFormat.JPEG
        imageFormat.equals("image/png", true) -> Bitmap.CompressFormat.PNG
        imageFormat.equals("image/webp", true) -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        else -> Bitmap.CompressFormat.PNG
    }
    return ImageSource.fromBitmapFactory(GpkgBitmapFactory(geoPackage, content, zoomLevel, column, gpkgRow, format))
}