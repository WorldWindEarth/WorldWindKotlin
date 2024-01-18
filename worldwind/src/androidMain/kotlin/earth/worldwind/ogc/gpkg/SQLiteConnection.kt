package earth.worldwind.ogc.gpkg

import android.database.sqlite.SQLiteDatabase
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

open class SQLiteConnection(val pathName: String, val flags: Int, keepAliveTime: Long, unit: TimeUnit) {
    protected var keepAliveTime = unit.toMillis(keepAliveTime)
    protected var job: Job? = null
    protected var database: SQLiteDatabase? = null
    protected val lock = Any()
    var isShutdown = false
        protected set

    fun setKeepAliveTime(time: Long, unit: TimeUnit) {
        keepAliveTime = unit.toMillis(time)
        restartJob()
    }

    fun openDatabase(): SQLiteDatabase? {
        if (isShutdown) return null
        synchronized(lock) {
            val database = database ?: SQLiteDatabase.openDatabase(pathName, null, flags).also {
                database = it
                logMessage(
                    INFO, "SQLiteConnection", "openDatabase", "SQLite connection opened $pathName"
                )
            }
            database.acquireReference()
            restartJob()
            return database
        }
    }

    fun shutdown() {
        isShutdown = true
        job?.cancel()
        job = null
        onConnectionTimeout()
    }

    protected open fun onConnectionTimeout() {
        synchronized(lock) {
            database?.close()
            logMessage(
                INFO, "SQLiteConnection", "onConnectionTimeout", "SQLite connection keep alive timeout $pathName"
            )
            if (database?.isOpen == true) {
                logMessage(
                    WARN, "SQLiteConnection", "onConnectionTimeout", "SQLite connection open after timeout $pathName"
                )
            }
            database = null
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    protected open fun restartJob() {
        job?.cancel()
        job = GlobalScope.launch(Dispatchers.Main) {
            delay(keepAliveTime)
            onConnectionTimeout()
        }
    }
}