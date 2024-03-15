package earth.worldwind.layer.atak

import android.graphics.Bitmap
import android.os.Build
import com.j256.ormlite.dao.Dao
import earth.worldwind.render.image.ImageSource

actual fun buildImageSource(
    tilesDao: Dao<ATAKTiles, Int>, readOnly: Boolean, contentKey: String, key: Int, imageFormat: String?
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
    return ImageSource.fromBitmapFactory(ATAKBitmapFactory(tilesDao, readOnly, contentKey, key, format))
}