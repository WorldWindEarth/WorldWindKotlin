package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Capabilities", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsCapabilities(
    val version: String,
    val updateSequence: String? = null,
    val serviceIdentification: OwsServiceIdentification? = null,
    val serviceProvider: OwsServiceProvider? = null,
    val operationsMetadata: OwsOperationsMetadata? = null,
    val contents: WmtsContents,
    @XmlSerialName("Themes", WMTS10_NAMESPACE, WMTS10_PREFIX)
    @XmlChildrenName("Theme", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val themes: List<WmtsTheme> = emptyList(),
    @XmlSerialName("ServiceMetadataURL", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val serviceMetadataUrls: List<OwsOnlineResource> = emptyList()
) {
    val layers get() = contents.layers
    val tileMatrixSets get() = contents.tileMatrixSets

    init {
        layers.forEach { layer -> layer.capabilities = this }
    }

    fun getLayer(identifier: String) = contents.layers.firstOrNull { layer -> layer.identifier == identifier }

    fun getTileMatrixSet(identifier: String) = contents.tileMatrixSets.firstOrNull { tms -> tms.identifier == identifier }
}