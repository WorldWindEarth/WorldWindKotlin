package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ServiceProvider", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsServiceProvider(
    @XmlElement
    @XmlSerialName("ProviderName", OWS11_NAMESPACE, OWS11_PREFIX)
    val providerName: String,
    @XmlSerialName("ProviderSite", OWS11_NAMESPACE, OWS11_PREFIX)
    val onlineResource: OwsOnlineResource? = null,
    val serviceContact: OwsServiceContact
) {
    val providerSiteUrl get() = onlineResource?.url
}