package earth.worldwind.util

import java.util.logging.Level
import java.util.logging.Logger

actual object Logger {
    actual val ERROR = Level.SEVERE.intValue()
    actual val WARN = Level.WARNING.intValue()
    actual val INFO = Level.INFO.intValue()
    actual val DEBUG = Level.ALL.intValue()

    private const val LOGGER_NAME = "worldwind"

    private fun getLevel(priority: Int) = when (priority) {
        ERROR -> Level.SEVERE
        WARN -> Level.WARNING
        INFO -> Level.INFO
        else -> Level.ALL
    }

    /**
     * Returns the WorldWind logger.
     */
    fun logger(): Logger = Logger.getLogger(LOGGER_NAME)

    @JvmStatic
    actual fun isLoggable(priority: Int) = logger().isLoggable(getLevel(priority))

    @JvmStatic
    @JvmOverloads
    actual fun log(priority: Int, message: String, tr: Throwable?) {
        logger().log(getLevel(priority), message, tr)
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