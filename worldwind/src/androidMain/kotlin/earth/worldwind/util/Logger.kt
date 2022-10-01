package earth.worldwind.util

import android.util.Log

actual object Logger {
    actual val ERROR = Log.ERROR
    actual val WARN = Log.WARN
    actual val INFO = Log.INFO
    actual val DEBUG = Log.DEBUG

    private const val TAG = "worldwind"

    @JvmStatic
    actual fun isLoggable(priority: Int) = Log.isLoggable(TAG, priority)

    @JvmStatic
    @JvmOverloads
    actual fun log(priority: Int, message: String, tr: Throwable?) {
        val messageWithTrace = tr?.run { message + '\n' + stackTraceToString() } ?: message
        if (Log.isLoggable(TAG, priority)) Log.println(priority, TAG, messageWithTrace)
    }

    @JvmStatic
    @JvmOverloads
    actual fun logMessage(level: Int, className: String, methodName: String, message: String, tr: Throwable?): String {
        val msg = makeMessage(className, methodName, message)
        log(level, msg, tr)
        return msg
    }

    @JvmStatic
    actual fun makeMessage(className: String, methodName: String, message: String) =
        "$className.$methodName: ${messageTable[message] ?: message}"
}