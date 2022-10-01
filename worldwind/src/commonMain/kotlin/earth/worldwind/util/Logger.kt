package earth.worldwind.util

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

expect object Logger {
    val ERROR: Int
    val WARN: Int
    val INFO: Int
    val DEBUG: Int

    @JvmStatic
    fun isLoggable(priority: Int): Boolean

    /**
     * Logs a specified message at a specified level.
     *
     * @param priority The logging level of the message. If the current logging level allows this message to be
     * logged it is written to the console.
     * @param message The message to log. Nothing is logged if the message is null or undefined.
     * @param tr Optional exception
     */
    @JvmStatic
    @JvmOverloads
    fun log(priority: Int, message: String, tr: Throwable? = null)

    @JvmStatic
    @JvmOverloads
    fun logMessage(level: Int, className: String, methodName: String, message: String, tr: Throwable? = null): String

    @JvmStatic
    fun makeMessage(className: String, methodName: String, message: String): String
}