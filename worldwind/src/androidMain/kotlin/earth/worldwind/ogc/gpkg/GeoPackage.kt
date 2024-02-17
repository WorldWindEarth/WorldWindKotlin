package earth.worldwind.ogc.gpkg

import android.database.sqlite.SQLiteDatabase.*
import com.j256.ormlite.android.AndroidConnectionSource
import com.j256.ormlite.support.ConnectionSource

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource = AndroidConnectionSource(
    openDatabase(pathName, null, if (readOnly) OPEN_READONLY else OPEN_READWRITE or CREATE_IF_NECESSARY)
)