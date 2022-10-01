package earth.worldwind.ogc.wmts

abstract class OwsDescription {
    abstract val title: String?
    abstract val abstract: String?
    abstract val keywords: List<String>
}