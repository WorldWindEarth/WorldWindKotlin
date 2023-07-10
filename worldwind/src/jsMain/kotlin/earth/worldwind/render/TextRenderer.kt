package earth.worldwind.render

import earth.worldwind.geom.Vec2
import earth.worldwind.render.image.CanvasTexture
import earth.worldwind.shape.TextAttributes
import kotlinx.browser.document
import org.w3c.dom.*
import kotlin.math.ceil

actual open class TextRenderer actual constructor(protected val rc: RenderContext) {
    private val lineSpacing = 0.15 // fraction of font size

    /**
     * Creates a texture for a specified text string and specified text attributes.
     *
     * @param text The text string.
     * @param attributes Text font, size, color etc.
     * @returns A texture for the specified text string.
     */
    actual fun renderText(text: String?, attributes: TextAttributes): Texture? =
        if (text?.isNotEmpty() == true) CanvasTexture(drawText(text, attributes)) else null

    /**
     * Creates a 2D Canvas for a specified text string while considering current TextRenderer state regarding outline
     * usage and color, text color, typeface, and outline width.
     *
     * @param text The text string.
     * @param attributes Text font, size, color etc.
     * @returns A 2D Canvas for the specified text string.
     */
    protected open fun drawText(text: String, attributes: TextAttributes): HTMLCanvasElement {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val ctx2D = canvas.getContext("2d") as CanvasRenderingContext2D

        val textSize = textSize(ctx2D, text, attributes)
        val lines = text.split("\n")
        val strokeOffset = if (attributes.isOutlineEnabled) attributes.outlineWidth / 2.0 else 0.0

        canvas.width = ceil(textSize.x * rc.densityFactor).toInt()
        canvas.height = ceil(textSize.y * rc.densityFactor).toInt()

        ctx2D.scale(rc.densityFactor.toDouble(), rc.densityFactor.toDouble())
        ctx2D.font = attributes.font.toString()
        ctx2D.textBaseline = CanvasTextBaseline.BOTTOM
        ctx2D.textAlign = attributes.font.horizontalAlignment
        ctx2D.fillStyle = attributes.textColor.toCssColorString()
        ctx2D.strokeStyle = attributes.outlineColor.toCssColorString()
        ctx2D.lineWidth = attributes.outlineWidth.toDouble()
        ctx2D.lineCap = CanvasLineCap.ROUND
        ctx2D.lineJoin = CanvasLineJoin.ROUND

        when (attributes.font.horizontalAlignment) {
            CanvasTextAlign.LEFT -> ctx2D.translate(strokeOffset, 0.0)
            CanvasTextAlign.RIGHT -> ctx2D.translate(textSize.x - strokeOffset, 0.0)
            else -> ctx2D.translate(textSize.x / 2.0, 0.0)
        }

        for (i in lines.indices) {
            val line = lines[i]
            ctx2D.translate(0.0, attributes.font.size * (1.0 + lineSpacing) + strokeOffset)
            if (attributes.isOutlineEnabled) ctx2D.strokeText(line, 0.0, 0.0)
            ctx2D.fillText(line, 0.0, 0.0)
        }

        return canvas
    }

    /**
     * Returns the width and height of a specified text string considering the current typeFace and outline usage.
     * @param ctx2D Canvas rendering context
     * @param text The text string.
     * @param textAttributes Text font, size, color etc.
     * @returns A vector indicating the text's width and height, respectively, in pixels.
     */
    protected open fun textSize(ctx2D: CanvasRenderingContext2D, text: String, textAttributes: TextAttributes): Vec2 {
        if (text.isEmpty()) Vec2()

        ctx2D.font = textAttributes.font.toString()

        val lines = text.split("\n")
        var height = lines.size * (textAttributes.font.size * (1.0 + lineSpacing))
        var maxWidth = 0.0
        for (i in lines.indices) maxWidth = maxWidth.coerceAtLeast(ctx2D.measureText(lines[i]).width)

        if (textAttributes.isOutlineEnabled) {
            maxWidth += textAttributes.outlineWidth
            height += textAttributes.outlineWidth
        }

        return Vec2(maxWidth, height)
    }
}