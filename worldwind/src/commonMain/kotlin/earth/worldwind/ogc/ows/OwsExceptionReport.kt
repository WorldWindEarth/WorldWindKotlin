package earth.worldwind.ogc.ows

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ExceptionReport", OWS20_NAMESPACE, OWS20_PREFIX)
data class OwsExceptionReport(
    val exceptions: List<OwsException> = emptyList(),
    val version: String,
    @XmlSerialName("lang", XML_NAMESPACE, XML_PREFIX)
    val lang: String? = null
) {
    override fun toString() = "OwsExceptionReport{exceptions=$exceptions, version='$version', lang='$lang'}"

    fun toPrettyString(): String? {
        return when (exceptions.size) {
            0 -> null
            1 -> exceptions[0].toPrettyString()
            else -> {
                val sb = StringBuilder()
                var ordinal = 1
                for (exception in exceptions) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(ordinal++).append(": ")
                    sb.append(exception.toPrettyString())
                }
                sb.toString()
            }
        }
    }
}