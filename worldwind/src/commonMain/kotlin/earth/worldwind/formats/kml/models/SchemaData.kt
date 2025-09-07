package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * This element is used in conjunction with [Schema] to add typed custom data to a KML [Feature].
 * The Schema element (identified by the schemaUrl attribute) declares the custom data type.
 * The actual data objects ("instances" of the custom data) are defined using the SchemaData element.
 *
 * The Schema element is always a child of Document.
 * The ExtendedData element is a child of the [Feature] that contains the custom data.
 */
@Serializable
internal data class SchemaData(
    /**
     * The [schemaUrl] can be a full URL, a reference to a Schema ID defined in an external KML file,
     * or a reference to a Schema ID defined in the same KML file.
     */
    val schemaUrl: String? = null,

    /**
     * This element assigns a value to the custom data field identified by the name attribute.
     * The type and name of this custom data field are declared in the [Schema] element.
     */
    @XmlElement
    val simpleData: List<SimpleData> = emptyList()
)