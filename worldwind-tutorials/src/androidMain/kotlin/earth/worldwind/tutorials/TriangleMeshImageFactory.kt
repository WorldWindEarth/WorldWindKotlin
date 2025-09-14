package earth.worldwind.tutorials

import android.graphics.*
import earth.worldwind.render.image.ImageSource

actual class TriangleMeshImageFactory actual constructor(
    private val size: Int, private val innerRadius: Float, private val outerRadius: Float
) : ImageSource.ImageFactory {
    override suspend fun createBitmap(): Bitmap? {
        val c = size / 2f - 0.5f

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val gradient = RadialGradient(
            c, c, outerRadius,
            intArrayOf(
                Color.rgb(255, 0, 0), // Red at center
                Color.rgb(0, 255, 0), // Green at 50%
                Color.rgb(255, 0, 0)  // Red at edge
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val paint = Paint().apply {
            shader = gradient
            isAntiAlias = true
        }

        canvas.drawCircle(c, c, outerRadius, paint)

        return bitmap
    }
}