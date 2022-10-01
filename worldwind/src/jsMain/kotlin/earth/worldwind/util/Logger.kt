package earth.worldwind.util

/**
 * Logs selected message types to the console.
 */
actual object Logger {
    actual val ERROR = 1
    actual val WARN = 2
    actual val INFO = 3
    actual val DEBUG = 4

    /**
     * Indicates the current logging level [ERROR], [WARN], [INFO] or [DEBUG].
     */
    var loggingLevel = ERROR

    actual fun isLoggable(priority: Int) = priority in ERROR until loggingLevel

    actual fun log(priority: Int, message: String, tr: Throwable?) {
        if (isLoggable(priority)) {
            val messageWithTrace = tr?.run { message + '\n' + stackTraceToString() } ?: message
            when (priority) {
                ERROR -> console.error(messageWithTrace)
                WARN -> console.warn(messageWithTrace)
                INFO -> console.info(messageWithTrace)
                else -> console.log(messageWithTrace)
            }
        }
    }

    actual fun logMessage(level: Int, className: String, methodName: String, message: String, tr: Throwable?) =
        makeMessage(className, methodName, message).also { log(level, it, tr) }

    actual fun makeMessage(className: String, methodName: String, message: String) =
        "$className.$methodName: ${messageTable[message] ?: message}"
}