package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * The ExtendedData element offers three techniques for adding custom data to a KML [Feature]
 * ([NetworkLink], [Placemark], [GroundOverlay], [PhotoOverlay], [ScreenOverlay], [Document], [Folder]).
 *
 * These techniques are:
 * - Adding untyped data/value pairs using the <Data> element (basic)
 * - Declaring new typed fields using the <Schema> element and then instancing them using the <SchemaData> element (advanced)
 * - Referring to XML elements defined in other namespaces by referencing the external namespace within the KML file (basic)
 *
 * These techniques can be combined within a single KML file or Feature for different pieces of data.
 * For more information, see Adding Custom Data in "Topics in KML."
 */
@Serializable
internal data class ExtendedData(
    @XmlElement
    val data: List<Data> = emptyList(),

    @XmlElement
    val schemaData: SchemaData? = null,
)