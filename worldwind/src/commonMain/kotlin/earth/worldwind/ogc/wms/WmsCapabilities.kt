package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("WMS_Capabilities", WMS_NAMESPACE, WMS_PREFIX)
data class WmsCapabilities(
    /**
     * Returns the document's version number.
     */
    val version: String = "1.3.0",
    /**
     * Returns the document's update sequence.
     */
    val updateSequence: String? = null,
    /**
     * Returns the document's service information.
     */
    val service: WmsService,
    val capability: WmsCapability
) {
    /**
     * Returns all named layers in the capabilities document.
     */
    val namedLayers get() = capability.layers.flatMap { layer -> layer.namedLayers }

    init {
        capability.capabilities = this
    }

    fun getNamedLayer(name: String) = namedLayers.firstOrNull { layer -> layer.name == name }
}