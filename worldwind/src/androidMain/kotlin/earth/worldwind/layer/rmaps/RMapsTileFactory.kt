package earth.worldwind.layer.rmaps

import android.graphics.Bitmap
import android.os.Build
import com.j256.ormlite.dao.Dao
import earth.worldwind.render.image.ImageSource

actual fun buildImageSource(
    tilesDao: Dao<RMapsTiles, *>, readOnly: Boolean, contentKey: String, x: Int, y: Int, z: Int, imageFormat: String?
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
    return ImageSource.fromImageFactory(RMapsImageFactory(tilesDao, readOnly, contentKey, x, y, z, format))
}