package earth.worldwind.layer.mercator.wiki

import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import kotlin.jvm.JvmOverloads

class WikiLayer @JvmOverloads constructor(
    private val type: Type = Type.HYBRID
) : MercatorTiledImageLayer(NAME + type.name.lowercase(), 23, 256, Type.HYBRID == type) {
    enum class Type { MAP, HYBRID; }

    companion object {
        const val NAME = "Wiki"
    }

    override fun getImageSourceUrl(x: Int, y: Int, z: Int): String {
        val i = x % 4 + y % 4 * 4
        val type = type.name.lowercase()
        return "http://i$i.wikimapia.org/?lng=1&x=$x&y=$y&zoom=$z&type=$type"
    }
}