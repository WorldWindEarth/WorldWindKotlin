package earth.worldwind.ogc.gml

abstract class GmlAbstractFeature: GmlAbstractGml() {
    abstract val boundedBy: GmlBoundingShape?
}