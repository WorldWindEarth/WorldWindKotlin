package earth.worldwind.layer.atak

import com.j256.ormlite.dao.Dao
import earth.worldwind.render.image.ImageSource

actual fun buildImageSource(
    tilesDao: Dao<ATAKTiles, Int>, readOnly: Boolean, contentKey: String, key: Int, imageFormat: String?
): ImageSource = TODO("Not yet implemented")