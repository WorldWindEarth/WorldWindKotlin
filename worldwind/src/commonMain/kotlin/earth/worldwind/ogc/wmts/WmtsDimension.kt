package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Dimension", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsDimension(
    @XmlElement
    @XmlSerialName("Title", OWS11_NAMESPACE, OWS11_PREFIX)
    override val title: String? = null,
    @XmlElement
    @XmlSerialName("Abstract", OWS11_NAMESPACE, OWS11_PREFIX)
    override val abstract: String? = null,
    @XmlSerialName("Keywords", OWS11_NAMESPACE, OWS11_PREFIX)
    @XmlChildrenName("Keyword", OWS11_NAMESPACE, OWS11_PREFIX)
    override val keywords: List<String> = emptyList(),
    @XmlElement
    @XmlSerialName("Identifier", OWS11_NAMESPACE, OWS11_PREFIX)
    val identifier: String,
    @XmlElement
    @XmlSerialName("UOM", OWS11_NAMESPACE, OWS11_PREFIX)
    val unitOfMeasure: String? = null,
    @XmlElement
    @XmlSerialName("UnitSymbol", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val unitSymbol: String? = null,
    @XmlElement
    @XmlSerialName("Default", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val valueDefault: String? = null,
    @XmlElement
    @XmlSerialName("Current", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val current: Boolean? = null,
    @XmlSerialName("Value", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val values: List<String> = emptyList()
): OwsDescription()