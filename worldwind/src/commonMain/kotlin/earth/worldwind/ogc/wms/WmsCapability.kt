package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Capability", WMS_NAMESPACE, WMS_PREFIX)
data class WmsCapability(
    val request: WmsRequest,
    val layers: List<WmsLayer> = emptyList(),
    /**
     * Object representation of an Exception element. Pre-allocated to prevent NPE in the event the server does not
     * include an Exception block.
     */
    val exception: WmsException = WmsException()
) {
    @Transient
    lateinit var capabilities: WmsCapabilities

    init {
        layers.forEach { layer -> layer.capability = this }
    }
}