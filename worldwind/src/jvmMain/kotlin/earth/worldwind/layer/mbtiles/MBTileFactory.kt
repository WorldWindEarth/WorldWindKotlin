package earth.worldwind.layer.mbtiles

import com.j256.ormlite.dao.Dao
import earth.worldwind.render.image.ImageSource

actual fun buildImageSource(
    tilesDao: Dao<MBTiles, *>, readOnly: Boolean, contentKey: String, zoom: Int, column: Int, row: Int, imageFormat: String?
): ImageSource = TODO("Not yet implemented")