package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Defines a key/value pair that maps a mode (normal or highlight) to the predefined <styleUrl>.
 */
@Serializable
@XmlSerialName("Pair")
internal data class StylePair(
    /**
     * Identifies the key
     */
    @XmlElement
    val key: String,

    /**
     * References the style. In <styleUrl>, for referenced style elements that are local to the KML document,
     * a simple # referencing is used. For styles that are contained in external files, use a full URL along with # referencing.
     */
    @XmlElement
    val styleUrl: String,
)