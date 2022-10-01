package earth.worldwind.ogc.gml

abstract class GmlAbstractGrid : GmlAbstractGeometry() {
    abstract val dimension: Int
    abstract val limits: GmlGridLimits
    abstract val axisName: List<String>
}