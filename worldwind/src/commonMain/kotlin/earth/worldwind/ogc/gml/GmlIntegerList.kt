package earth.worldwind.ogc.gml

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
data class GmlIntegerList(
    @XmlValue(true)
    private val integers: String
) {
    val values get() = try {
        integers.split(" ").let{ tokens -> IntArray(tokens.size) { tokens[it].toInt() } }
    } catch (e: NumberFormatException) {
        logMessage(ERROR, "GmlIntegerList", "values", "exceptionParsingText", e)
        throw e
    }
}