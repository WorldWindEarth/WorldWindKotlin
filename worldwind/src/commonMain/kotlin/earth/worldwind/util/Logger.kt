package earth.worldwind.util

expect object Logger {
    val ERROR: Int
    val WARN: Int
    val INFO: Int
    val DEBUG: Int

    fun isLoggable(priority: Int): Boolean

    /**
     * Logs a specified message at a specified level.
     *
     * @param priority The logging level of the message. If the current logging level allows this message to be
     * logged it is written to the console.
     * @param message The message to log. Nothing is logged if the message is null or undefined.
     * @param tr Optional exception
     */
    fun log(priority: Int, message: String, tr: Throwable? = null)

    fun logMessage(level: Int, className: String, methodName: String, message: String, tr: Throwable? = null): String

    fun makeMessage(className: String, methodName: String, message: String): String
}