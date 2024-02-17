package earth.worldwind.ogc.gpkg

import com.j256.ormlite.jdbc.JdbcConnectionSource
import com.j256.ormlite.support.ConnectionSource

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource =
    JdbcConnectionSource("jdbc:sqlite:$pathName")
