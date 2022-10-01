package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Position
import earth.worldwind.shape.PlacemarkAttributes
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

expect open class MilStd2525Placemark @JvmOverloads constructor(
    symbolCode: String,
    position: Position,
    symbolModifiers: Map<String, String>? = null,
    symbolAttributes: Map<String, String>? = null
) : AbstractMilStd2525Placemark {
    companion object {
        /**
         * Releases cached PlacemarkAttribute bundles.
         */
        @JvmStatic
        fun clearSymbolCache()

        @JvmStatic
        fun getPlacemarkAttributes(
            symbolCode: String,
            symbolModifiers: Map<String, String>? = null,
            symbolAttributes: Map<String, String>? = null
        ): PlacemarkAttributes
    }
}