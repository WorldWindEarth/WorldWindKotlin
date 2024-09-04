package earth.worldwind.layer.rmaps

import com.j256.ormlite.dao.Dao
import earth.worldwind.render.image.ImageSource

actual fun buildImageSource(
    tilesDao: Dao<RMapsTiles, *>, readOnly: Boolean, contentKey: String, x: Int, y: Int, z: Int, imageFormat: String?
): ImageSource = TODO("Not yet implemented")