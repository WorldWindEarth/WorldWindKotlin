package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * Creates an untyped name/value pair. The name can have two versions: name and displayName.
 * The name attribute is used to identify the data pair within the KML file.
 * The displayName element is used when a properly formatted name, with spaces and HTML formatting, is displayed in Google Earth.
 * In the <text> element of <BalloonStyle>, the notation $[name/displayName] is replaced with <displayName>.
 * If you substitute the value of the name attribute of the <Data> element in this format (for example, $[holeYardage],
 * the attribute value is replaced with <value>. By default, the Placemark's balloon displays the name/value pairs associated with it.
 */
@Serializable
internal data class Data(
    /**
     * The name attribute is used to identify the data pair within the KML file.
     */
    val name: String,

    /**
     * An optional formatted version of name, to be used for display purposes.
     */
    @XmlElement
    val displayName: String? = null,

    /**
     * Value of the data pair.
     */
    @XmlElement
    val value: String
)