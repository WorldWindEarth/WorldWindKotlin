package earth.worldwind.tutorials

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_TEXTURE_2D
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource

/**
 * A moving test card for sensors that have no video: colour bars scrolling sideways, a clock hand
 * going round once every [handPeriodSeconds], and a white border with a marker in the top left
 * corner, so the picture's orientation on the ground can be read. Drawn on the CPU into a small
 * RGBA image and uploaded at most [framesPerSecond] times a second, however many frames bind it,
 * so one texture can be shared by every sensor.
 */
class TestPatternTexture(
    size: Int = 128,
    private val framesPerSecond: Int = 15,
    private val handPeriodSeconds: Double = 10.0,
) : Texture(size, size, GL_RGBA, GL_UNSIGNED_BYTE) {
    private val pixels = ByteArray(size * size * 4)
    private val clock = TimeSource.Monotonic.markNow()
    private var uploadedTick = -1L

    init {
        generateMipmaps = false
        paint(0.0)
    }

    override fun allocTexImage(dc: DrawContext) {
        dc.gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
    }

    override fun bindTexture(dc: DrawContext): Boolean {
        if (!super.bindTexture(dc)) return false
        val seconds = clock.elapsedNow().inWholeMicroseconds / 1e6
        val tick = (seconds * framesPerSecond).toLong()
        if (tick != uploadedTick) {
            uploadedTick = tick
            paint(seconds)
            dc.gl.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        }
        return true
    }

    private fun paint(seconds: Double) {
        val size = width
        val shift = (seconds * size / 4).toInt() // a quarter of the card a second
        val hand = 2 * PI * seconds / handPeriodSeconds
        val hx = sin(hand)
        val hy = cos(hand)
        val c = (size - 1) / 2.0
        for (row in 0 until size) for (col in 0 until size) {
            val bar = (((col + shift) % size) * BARS.size / size)
            var rgb = BARS[bar]
            // The clock: a ring and a hand from the centre
            val dx = col - c
            val dy = (size - 1 - row) - c // rows run bottom up in the texture: make +y up
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            val along = dx * hx + dy * hy
            val across = abs(dx * hy - dy * hx)
            if (abs(r - size * 0.35) < 1.5 || (along in 0.0..size * 0.33 && across < 2.0)) rgb = 0x000000
            // Border, and a marker in the top left corner
            val edge = row < 3 || col < 3 || row >= size - 3 || col >= size - 3
            if (edge || (col < size / 6 && row >= size - size / 6)) rgb = 0xFFFFFF
            val i = 4 * (row * size + col)
            pixels[i] = (rgb shr 16).toByte()
            pixels[i + 1] = (rgb shr 8).toByte()
            pixels[i + 2] = rgb.toByte()
            pixels[i + 3] = 0xFF.toByte()
        }
    }

    private companion object {
        /** SMPTE-like colour bars. */
        val BARS = intArrayOf(0xC0C0C0, 0xC0C000, 0x00C0C0, 0x00C000, 0xC000C0, 0xC00000, 0x0000C0)
    }
}
