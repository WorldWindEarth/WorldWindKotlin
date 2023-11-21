package earth.worldwind.ogc.gml

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

abstract class GmlDoubleList {
    protected abstract val doubles: String
    val values get() = try {
        doubles.split(" ").let { tokens -> DoubleArray(tokens.size) { tokens[it].toDouble() } }
    } catch (e: NumberFormatException) {
        logMessage(ERROR, "GmlDoubleList", "values", "exceptionParsingText", e)
        throw e
    }
}