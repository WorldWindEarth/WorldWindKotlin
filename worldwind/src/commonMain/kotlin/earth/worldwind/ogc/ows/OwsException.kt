package earth.worldwind.ogc.ows

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Exception", OWS20_NAMESPACE, OWS20_PREFIX)
data class OwsException(
    @XmlSerialName("ExceptionText", OWS20_NAMESPACE, OWS20_PREFIX)
    val exceptionText: List<String> = emptyList(),
    val exceptionCode: String,
    val locator: String? = null
) {
    override fun toString() = "OwsException{exceptionText=$exceptionText, exceptionCode='$exceptionCode', locator='$locator'}"

    fun toPrettyString(): String? {
        return when (exceptionText.size) {
            0 -> null
            1 -> exceptionText[0]
            else -> exceptionText.joinToString(", ")
        }
    }
}