package earth.worldwind.sensor

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.kgl.GL_CLAMP_TO_EDGE
import earth.worldwind.util.kgl.GL_LINEAR
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_TEXTURE_MAG_FILTER
import earth.worldwind.util.kgl.GL_TEXTURE_MIN_FILTER
import earth.worldwind.util.kgl.GL_TEXTURE_WRAP_S
import earth.worldwind.util.kgl.GL_TEXTURE_WRAP_T
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The hidden ground of a [Viewshed] as an image for the GPU: one texel per cell of the polar
 * grid, or per block of cells when the grid has more than [maxTexels], its value the hidden
 * margin scaled so that half grey is the boundary, with a one-texel border of not hidden all
 * round so that sampling past the grid finds nothing. [earth.worldwind.draw.DrawableSensorFrames]
 * draws hatching in screen space wherever the terrain falls in a hidden texel, so the hatching
 * matches the viewshed's outline and does not change with the zoom.
 */
class ViewshedMask internal constructor(
    /** Azimuth of the first column's left edge, radians clockwise from north. */
    val startAzimuth: Double,
    /** Angular span of all the columns, radians, and ground reach of all the bins, metres. */
    val span: Double,
    val reach: Double,
    /** Texels across the columns and the bins, and the image size with the border. */
    val columns: Int,
    val bins: Int,
    val width: Int,
    val height: Int,
    /** RGBA bytes, a row per bin, the value in every channel. */
    val data: ByteArray,
) {
    val byteCount get() = data.size

    /** The GPU texture; made on the drawing thread and kept by the render resource cache under this mask. */
    internal fun newTexture(): Texture = MaskTexture(width, height, data)

    companion object {
        /** Most texels a mask has; a larger grid is sampled by square blocks, the most hidden cell of each. */
        var maxTexels = 1024 * 1024

        internal fun of(viewshed: Viewshed): ViewshedMask {
            val step = max(1, ceil(sqrt(viewshed.columns.toDouble() * viewshed.bins / maxTexels)).toInt())
            val columns = (viewshed.columns + step - 1) / step
            val bins = (viewshed.bins + step - 1) / step
            val width = columns + 2
            val height = bins + 2
            val data = ByteArray(4 * width * height)
            val scale = 2.0 * viewshed.binLength * step
            for (b in 0 until bins) for (c in 0 until columns) {
                var m = Float.NEGATIVE_INFINITY
                for (bb in b * step until min((b + 1) * step, viewshed.bins)) {
                    for (cc in c * step until min((c + 1) * step, viewshed.columns)) {
                        m = max(m, viewshed.hiddenMargin[cc * viewshed.bins + bb])
                    }
                }
                val v = ((0.5 + m / scale).coerceIn(0.0, 1.0) * 255.0).toInt().toByte()
                val o = 4 * ((b + 1) * width + (c + 1))
                data[o] = v; data[o + 1] = v; data[o + 2] = v; data[o + 3] = -1
            }
            return ViewshedMask(
                viewshed.startAzimuth, viewshed.columnAngle * viewshed.columns, viewshed.binLength * viewshed.bins,
                columns, bins, width, height, data,
            )
        }
    }
}

/** A texture uploaded from bytes, sampled bilinearly and clamped at its edges. */
internal class MaskTexture(width: Int, height: Int, private val data: ByteArray) :
    Texture(width, height, GL_RGBA, GL_UNSIGNED_BYTE) {
    init {
        setTexParameter(GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        setTexParameter(GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        setTexParameter(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        setTexParameter(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    override fun allocTexImage(dc: DrawContext) {
        dc.gl.texImage2D(target, 0, internalFormat, width, height, 0, format, type, data)
    }
}
