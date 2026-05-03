package earth.worldwind

import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.layer.Layer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import kotlin.jvm.JvmStatic
import kotlin.math.roundToInt

open class PickedObject protected constructor(
    val identifier: Int, val userObject: Any, val layer: Layer? = null, val terrainPosition: Position? = null
) {
    var isOnTop = false
        protected set

    val isTerrain get() = terrainPosition != null

    /**
     * Cartesian point on the picked surface, reconstructed from the depth buffer at pick time.
     * Set only on non-terrain top picks (terrain picks expose the geographic location via
     * [terrainPosition] instead). Null when no shape was hit, or when the pick produced no
     * usable depth value (e.g. cleared background).
     */
    var cartesianPoint: Vec3? = null
        internal set

    /**
     * Geographic equivalent of [cartesianPoint] on the given [globe], or `null` when no depth
     * value was usable.
     */
    fun geographicPoint(globe: Globe, result: Position = Position()) =
        cartesianPoint?.let { globe.cartesianToGeographic(it.x, it.y, it.z, result) }

    companion object {
        @JvmStatic
        fun fromRenderable(identifier: Int, renderable: Renderable, layer: Layer) =
            PickedObject(identifier, renderable.pickDelegate ?: renderable, layer)

        @JvmStatic
        fun fromTerrain(identifier: Int, position: Position): PickedObject {
            val positionCopy = Position(position)
            return PickedObject(identifier, positionCopy, terrainPosition = positionCopy)
        }

        @JvmStatic
        fun identifierToUniqueColor(identifier: Int, result: Color): Color {
            val r8 = identifier shr 16 and 0xFF
            val g8 = identifier shr 8 and 0xFF
            val b8 = identifier and 0xFF
            result.red = r8 / 0xFF.toFloat()
            result.green = g8 / 0xFF.toFloat()
            result.blue = b8 / 0xFF.toFloat()
            result.alpha = 1f
            return result
        }

        @JvmStatic
        fun uniqueColorToIdentifier(color: Color): Int {
            val r8 = (color.red * 0xFF).roundToInt()
            val g8 = (color.green * 0xFF).roundToInt()
            val b8 = (color.blue * 0xFF).roundToInt()
            return r8 shl 16 or (g8 shl 8) or b8
        }
    }

    internal fun markOnTop() { isOnTop = true }

    override fun toString() = "PickedObject(isOnTop=$isOnTop, identifier=$identifier, userObject=$userObject, layer=$layer, terrainPosition=$terrainPosition, cartesianPoint=$cartesianPoint)"
}