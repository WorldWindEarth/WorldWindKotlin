package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Contents", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsContents(
    val layers: List<WmtsLayer> = emptyList(),
    val tileMatrixSets: List<WmtsTileMatrixSet> = emptyList()
)