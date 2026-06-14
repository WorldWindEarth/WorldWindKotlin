package earth.worldwind.util.ormlite

import android.database.sqlite.SQLiteDatabase.*
import com.j256.ormlite.android.AndroidConnectionSource
import com.j256.ormlite.support.ConnectionSource

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource = AndroidConnectionSource(
    openDatabase(
        pathName, null,
        if (readOnly) OPEN_READONLY else OPEN_READWRITE or CREATE_IF_NECESSARY or ENABLE_WRITE_AHEAD_LOGGING
    ).also {
        if (!it.isReadOnly) {
            // Android SQLiteDatabase exposes no opening-time API for these, so PRAGMA after open.
            it.execSQL("PRAGMA synchronous = NORMAL")
            it.execSQL("PRAGMA cache_size = -64000")
            it.execSQL("PRAGMA wal_autocheckpoint = 1000")
        }
    }
)