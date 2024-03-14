package earth.worldwind.layer.atak

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.jdbc.JdbcConnectionSource
import com.j256.ormlite.support.ConnectionSource
import earth.worldwind.render.image.ImageSource

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource =
    JdbcConnectionSource("jdbc:sqlite:$pathName")

actual fun buildImageSource(
    tilesDao: Dao<ATAKTiles, Int>, readOnly: Boolean, contentKey: String, key: Int, imageFormat: String?
): ImageSource = TODO("Not yet implemented")