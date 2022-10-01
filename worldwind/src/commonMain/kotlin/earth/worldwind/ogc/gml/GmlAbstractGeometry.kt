package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable

@Serializable
abstract class GmlAbstractGeometry: GmlAbstractGml() {
    abstract val srsName: String?
    abstract val srsDimension: String?
    protected abstract val axisLabels: String?
    protected abstract val uomLabels: String?
    val axisLabelsList get() = axisLabels?.split(" ") ?: emptyList()
    val uomLabelsList get() = uomLabels?.split(" ") ?: emptyList()
}