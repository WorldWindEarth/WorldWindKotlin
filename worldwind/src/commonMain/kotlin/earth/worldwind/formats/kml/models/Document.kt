package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

/**
 * A Document is a container for features and styles. This element is required if your KML file uses shared styles.
 * It is recommended that you use shared styles, which require the following steps:
 *
 * 1. Define all Styles in a Document. Assign a unique ID to each Style.
 * 2. Within a given Feature or StyleMap, reference the Style's ID using a <styleUrl> element.
 *
 * Note that shared styles are not inherited by the Features in the Document.
 *
 * Each Feature must explicitly reference the styles it uses in a [styleUrl] element.
 * For a Style that applies to a Document (such as [ListStyle]), the [Document] itself must explicitly reference
 * the [styleUrl]. For example:
 *
 * <Document>
 *   <Style id="myPrettyDocument">
 *    <ListStyle> ... </ListStyle>
 *
 *   </Style>
 *   <styleUrl#myPrettyDocument">
 *   ...
 * </Document>
 *
 * Do not put shared styles within a [Folder].
 */
@Serializable
internal data class Document(
    override val id: String? = null,

    /**
     * Define all Styles in a Document. Assign a unique ID to each Style.
     */
    @XmlElement
    val styles: List<Style> = emptyList(),

    /**
     * Within a given Feature or StyleMap, reference the Style's ID using a [styleUrl] element.
     */
    @XmlElement
    val styleMaps: List<StyleMap> = emptyList(),

    /**
     * Specifies a custom KML schemas that is used to add custom data to KML Features.
     */
    @XmlElement
    val schemas: List<Schema> = emptyList(),
) : Container()

