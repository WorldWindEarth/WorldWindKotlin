package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Represents a single moment in time. This is a simple element and contains no children. Its value is a dateTime,
 * specified in XML time (see XML Schema Part 2: Datatypes Second Edition). The precision of the TimeStamp is dictated
 * by the dateTime value in the [when] element.
 */
@Serializable
internal data class TimeStamp(
    override val id: String? = null,

    /**
     * Specifies a single moment in time. The value is a dateTime, which can be one of the following:
     * - dateTime (YYYY-MM-DDThh:mm:ssZ) or (YYYY-MM-DDThh:mm:sszzzzzz) gives second resolution
     * - date (YYYY-MM-DD) gives day resolution
     * - gYearMonth (YYYY-MM) gives month resolution
     * - gYear (YYYY) gives year resolution
     */
    @XmlSerialName("when")
    @XmlElement
    val timestamp: String? = null,
) : TimePrimitive()