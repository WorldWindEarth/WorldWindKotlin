package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("CoverageOfferingBrief", WCS10_NAMESPACE, WCS10_PREFIX)
data class Wcs100CoverageOfferingBrief(
    @XmlElement
    val name: String,
    @XmlElement
    val label: String,
    @XmlElement
    val description: String,
    @XmlSerialName("lonLatEnvelope", WCS10_NAMESPACE, WCS10_PREFIX)
    val lonLatEnvelope: Wcs100LonLatEnvelope,
    @XmlSerialName("keywords")
    @XmlChildrenName("keyword")
    val keywords: List<String> = emptyList(),
)