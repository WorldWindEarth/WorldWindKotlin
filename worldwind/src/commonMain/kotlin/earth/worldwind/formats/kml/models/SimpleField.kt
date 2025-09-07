package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * A [Schema] element contains one or more [SimpleField] elements. In the [SimpleField], the [Schema] declares the type
 * and name of the custom field. It optionally specifies a displayName (the user-friendly form, with spaces and proper
 * punctuation used for display in Google Earth) for this custom field.
 */
@Serializable
internal data class SimpleField(
    /**
     * Type of custom field
     */
    val type: String,

    /**
     * Name of custom field
     */
    val name: String,

    /**
     * The name, if any, to be used when the field name is displayed to the Google Earth user.
     * Use the [CDATA] element to escape standard HTML markup.
     */
    @XmlElement
    val displayName: String? = null,
) : AbstractKml()