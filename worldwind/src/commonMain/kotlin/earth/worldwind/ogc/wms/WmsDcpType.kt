package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("DCPType", WMS_NAMESPACE, WMS_PREFIX)
data class WmsDcpType(
    val http: WmsHttp
) {
    val getHref get() = http.get.onlineResource.url
    val postHref get() = http.post?.onlineResource?.url

    @Serializable
    @XmlSerialName("HTTP", WMS_NAMESPACE, WMS_PREFIX)
    data class WmsHttp(
        @XmlSerialName("Get", WMS_NAMESPACE, WMS_PREFIX)
        val get: WmsHttpProtocol,
        @XmlSerialName("Post", WMS_NAMESPACE, WMS_PREFIX)
        val post: WmsHttpProtocol? = null,
    )

    @Serializable
    data class WmsHttpProtocol(
        val onlineResource: WmsOnlineResource
    )
}