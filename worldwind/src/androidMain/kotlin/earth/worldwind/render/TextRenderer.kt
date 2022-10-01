package earth.worldwind.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import earth.worldwind.render.image.BitmapTexture
import earth.worldwind.shape.TextAttributes
import kotlin.math.ceil

actual open class TextRenderer actual constructor(protected val rc: RenderContext) {
    protected val canvas = Canvas()
    protected val paint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT }
    private val scratchBounds = Rect()

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
        paint.getTextBounds(text, 0, text.length, scratchBounds)
        var x = -scratchBounds.left + 1f
        var y = -scratchBounds.top + 1f
        var width = scratchBounds.width() + 2
        var height = scratchBounds.height() + 2
        if (attributes.isEnableOutline) {
            val strokeWidth2 = ceil(paint.strokeWidth * 0.5f).toInt()
            x += strokeWidth2
            y += strokeWidth2
            width += strokeWidth2 * 2
            height += strokeWidth2 * 2
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas.setBitmap(bitmap)
        if (attributes.isEnableOutline) {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.color = attributes.outlineColor.toColorInt()
            canvas.drawText(text, 0, text.length, x, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = attributes.textColor.toColorInt()
        canvas.drawText(text, 0, text.length, x, y, paint)
        canvas.setBitmap(null)
        return bitmap
    }
}