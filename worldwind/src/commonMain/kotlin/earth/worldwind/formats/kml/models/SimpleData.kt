package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

/**
 * This element assigns a value to the custom data field identified by the name attribute.
 * The type and name of this custom data field are declared in the [Schema] element.
 */
@Serializable
internal data class SimpleData(val name: String, @XmlValue val value: String)