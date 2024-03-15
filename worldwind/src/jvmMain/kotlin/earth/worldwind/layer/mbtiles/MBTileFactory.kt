package earth.worldwind.layer.mbtiles

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.jdbc.JdbcConnectionSource
import com.j256.ormlite.support.ConnectionSource
import earth.worldwind.render.image.ImageSource

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource =
    JdbcConnectionSource("jdbc:sqlite:$pathName")

actual fun buildImageSource(
    tilesDao: Dao<MBTiles, Int>, readOnly: Boolean, contentKey: String, zoom: Int, column: Int, row: Int, imageFormat: String?
): ImageSource = TODO("Not yet implemented")