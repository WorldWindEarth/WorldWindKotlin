package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * Specifies a custom KML schema that is used to add custom data to KML Features.
 * The [id] attribute is required and must be unique within the KML file.
 * [Schema] is always a child of [Document].
 */
@Serializable
internal data class Schema(
    /**
     * Unique [Schema] ID
     */
    val id: String,

    /**
     * Human-readable [Schema] name
     */
    val name: String? = null,

    /**
     * A [Schema] element contains one or more [SimpleField] elements.
     */
    @XmlElement
    val simpleFields: List<SimpleField> = emptyList()
)