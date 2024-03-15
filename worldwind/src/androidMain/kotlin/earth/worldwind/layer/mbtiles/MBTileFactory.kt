package earth.worldwind.layer.mbtiles

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Build
import com.j256.ormlite.android.AndroidConnectionSource
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.support.ConnectionSource
import earth.worldwind.render.image.ImageSource

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource = AndroidConnectionSource(
    SQLiteDatabase.openDatabase(pathName, null, if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
)

actual fun buildImageSource(
    tilesDao: Dao<MBTiles, Int>, readOnly: Boolean, contentKey: String, zoom: Int, column: Int, row: Int, imageFormat: String?
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
    return ImageSource.fromBitmapFactory(MBTilesBitmapFactory(tilesDao, readOnly, contentKey, zoom, column, row, format))
}