package earth.worldwind.ogc.wfs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("FeatureTypeList", WFS20_NAMESPACE, WFS20_PREFIX)
data class WfsFeatureTypeList(
    val featureTypes: List<WfsFeatureType> = emptyList()
) : Iterable<WfsFeatureType> by featureTypes
