package earth.worldwind.layer.mercator.google

import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.util.locale.language

class GoogleLayer(type: Type): MercatorTiledImageLayer(type.layerName, 22, 256, type.overlay) {
    private val lyrs = type.lyrs

    enum class Type(val layerName: String, val lyrs: String, val overlay: Boolean) {
        ROADMAP("Google road map", "m", false),
        ROADMAP2("Google road map 2", "r", false),
        TERRAIN("Google map w/ terrain", "p", false),
        TERRAIN_ONLY("Google terrain only", "t", false),
        HYBRID("Google hybrid", "y", false),
        SATELLITE("Google satellite", "s", false),
        ROADS("Google roads", "h", true),
        TRAFFIC("Google traffic", "h,traffic&style=15", true);
    }

    override fun getImageSourceUrl(x: Int, y: Int, z: Int): String {
        return "https://mt.google.com/vt/lyrs=$lyrs&x=$x&y=$y&z=$z&hl=$language"
    }
}