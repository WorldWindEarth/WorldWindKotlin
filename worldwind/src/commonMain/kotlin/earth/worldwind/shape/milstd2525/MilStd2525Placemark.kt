package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Position
import earth.worldwind.shape.PlacemarkAttributes

expect open class MilStd2525Placemark(
    symbolCode: String,
    position: Position,
    symbolModifiers: Map<String, String>? = null,
    symbolAttributes: Map<String, String>? = null
) : AbstractMilStd2525Placemark {
    companion object {
        fun clearSymbolCache()

        fun getPlacemarkAttributes(
            symbolCode: String,
            symbolModifiers: Map<String, String>? = null,
            symbolAttributes: Map<String, String>? = null
        ): PlacemarkAttributes
    }
}