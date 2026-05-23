package earth.worldwind.ogc.wfs

import earth.worldwind.ogc.wmts.OwsOperationsMetadata
import earth.worldwind.ogc.wmts.OwsServiceIdentification
import earth.worldwind.ogc.wmts.OwsServiceProvider
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("WFS_Capabilities", WFS20_NAMESPACE, WFS20_PREFIX)
data class WfsCapabilities(
    val version: String = "2.0.0",
    val updateSequence: String? = null,
    val serviceIdentification: OwsServiceIdentification? = null,
    val serviceProvider: OwsServiceProvider? = null,
    val operationsMetadata: OwsOperationsMetadata? = null,
    val featureTypeList: WfsFeatureTypeList = WfsFeatureTypeList(),
) {
    val featureTypes get() = featureTypeList.featureTypes

    init {
        featureTypes.forEach { it.capabilities = this }
    }

    fun getFeatureType(name: String) = featureTypes.firstOrNull { ft -> ft.name == name }
}
