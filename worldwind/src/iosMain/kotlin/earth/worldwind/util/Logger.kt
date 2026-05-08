package earth.worldwind.util

import platform.Foundation.NSLog

actual object Logger {
    actual val ERROR = 1
    actual val WARN = 2
    actual val INFO = 3
    actual val DEBUG = 4

    var loggingLevel = ERROR

    actual fun isLoggable(priority: Int) = priority in ERROR..loggingLevel

    actual fun log(priority: Int, message: String, tr: Throwable?) {
        if (isLoggable(priority)) {
            val text = if (tr != null) "$message\n${tr.stackTraceToString()}" else message
            NSLog("%s", text)
        }
    }

    actual fun logMessage(level: Int, className: String, methodName: String, message: String, tr: Throwable?) =
        makeMessage(className, methodName, message).also { log(level, it, tr) }

    actual fun makeMessage(className: String, methodName: String, message: String) =
        "$className.$methodName: ${messageTable[message] ?: message}"
}
