package earth.worldwind.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import earth.worldwind.geom.Vec2
import earth.worldwind.render.image.BitmapTexture
import earth.worldwind.shape.TextAttributes
import kotlin.math.ceil

actual open class TextRenderer actual constructor(protected val rc: RenderContext) {
    protected val canvas = Canvas()
    protected val paint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT }
    private val scratchBounds = Rect()
    private val scratchOffset = Vec2()

    /**
     * Creates a texture for a specified text string and specified text attributes.
     *
     * @param text The text string.
     * @returns A texture for the specified text string.
     */
    actual fun renderText(text: String?, attributes: TextAttributes): Texture? =
        if (text?.isNotEmpty() == true) BitmapTexture(drawText(text, attributes)) else null

    /**
     * Creates a [Bitmap] for a specified text string while considering current TextRenderer state regarding outline
     * usage and color, text color, typeface, and outline width.
     *
     * @param text The text string.
     * @param attributes Text font, size, color etc.
     * @returns A [Bitmap] for the specified text string.
     */
    protected open fun drawText(text: String, attributes: TextAttributes): Bitmap {
        paint.typeface = attributes.font.typeface
        paint.textSize = attributes.font.size
        paint.strokeWidth = attributes.outlineWidth
        // Horizontal extent from the glyphs' tight box, but vertical extent from the FONT metrics
        // (constant ascent/descent) so every label shares one baseline — else descenders ride high.
        val fm = paint.fontMetrics
        val ascentPx = ceil(-fm.top)
        val descentPx = ceil(fm.bottom)
        val lineHeight = ascentPx + descentPx
        val lines = text.split("\n")
        val lineX = FloatArray(lines.size)
        val lineWidths = IntArray(lines.size)
        var maxLineWidth = 0
        for (i in lines.indices) {
            val line = lines[i]
            paint.getTextBounds(line, 0, line.length, scratchBounds)
            lineX[i] = -scratchBounds.left + 1f
            lineWidths[i] = scratchBounds.width()
            maxLineWidth = maxLineWidth.coerceAtLeast(lineWidths[i])
        }
        var strokePad = 0
        var width = maxLineWidth + 2
        var height = (lineHeight * lines.size).toInt() + 2
        if (attributes.isOutlineEnabled) {
            strokePad = ceil(paint.strokeWidth * 0.5f).toInt()
            width += strokePad * 2
            height += strokePad * 2
        }
        // Ragged lines follow the anchor: offset fraction 0 = left, 0.5 = center, 1 = right.
        val align = (attributes.textOffset.offsetForSize(width.toDouble(), height.toDouble(), scratchOffset).x / width).coerceIn(0.0, 1.0)
        for (i in lines.indices) lineX[i] += strokePad + ((maxLineWidth - lineWidths[i]) * align).toFloat()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas.setBitmap(bitmap)
        val firstBaseline = ascentPx + 1f + strokePad // baseline at the constant ascent from the top
        if (attributes.isOutlineEnabled) {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.color = attributes.outlineColor.toColorInt()
            drawLines(lines, lineX, firstBaseline, lineHeight)
        }
        paint.style = Paint.Style.FILL
        paint.color = attributes.textColor.toColorInt()
        drawLines(lines, lineX, firstBaseline, lineHeight)
        canvas.setBitmap(null)
        return bitmap
    }

    private fun drawLines(lines: List<String>, lineX: FloatArray, firstBaseline: Float, lineHeight: Float) {
        var y = firstBaseline
        for (i in lines.indices) {
            val line = lines[i]
            canvas.drawText(line, 0, line.length, lineX[i], y, paint)
            y += lineHeight
        }
    }
}