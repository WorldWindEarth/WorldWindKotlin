package earth.worldwind.util

import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import kotlin.js.JsAny
import kotlin.js.js

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

    actual fun isLoggable(priority: Int) = priority in ERROR..loggingLevel

    actual fun log(priority: Int, message: String, tr: Throwable?) {
        if (isLoggable(priority)) {
            val messageWithTrace = tr?.run { message + '\n' + stackTraceToString() } ?: message
            when (priority) {
                ERROR -> jsConsole.error(messageWithTrace)
                WARN -> jsConsole.warn(messageWithTrace)
                INFO -> jsConsole.info(messageWithTrace)
                else -> jsConsole.log(messageWithTrace)
            }
        }
    }

    actual fun logMessage(level: Int, className: String, methodName: String, message: String, tr: Throwable?) =
        makeMessage(className, methodName, message).also { log(level, it, tr) }

    actual fun makeMessage(className: String, methodName: String, message: String) =
        "$className.$methodName: ${messageTable[message] ?: message}"
}

// Kotlin/Wasm has no `kotlin.js.console`; bind the global console as a typed external instead so
// the String argument is marshalled to a JS string on every call.
private external interface JsConsole : JsAny {
    fun error(message: String)
    fun warn(message: String)
    fun info(message: String)
    fun log(message: String)
}
private val jsConsole: JsConsole = js("console")