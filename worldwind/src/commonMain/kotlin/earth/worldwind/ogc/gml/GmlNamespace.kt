package earth.worldwind.ogc.gml

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

const val GML32_NAMESPACE = "http://www.opengis.net/gml/3.2"
const val GML32_PREFIX = "gml"

val serializersModule = SerializersModule {
    polymorphic(GmlAbstractGeometry::class) {
        subclass(GmlPoint::class)
        subclass(GmlGrid::class)
        subclass(GmlRectifiedGrid::class)
    }
}