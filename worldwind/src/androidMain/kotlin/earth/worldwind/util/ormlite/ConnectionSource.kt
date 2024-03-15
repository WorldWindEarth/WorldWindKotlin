package earth.worldwind.util.ormlite

import android.database.sqlite.SQLiteDatabase
import com.j256.ormlite.android.AndroidConnectionSource
import com.j256.ormlite.support.ConnectionSource

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource = AndroidConnectionSource(
    SQLiteDatabase.openDatabase(pathName, null, if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
)