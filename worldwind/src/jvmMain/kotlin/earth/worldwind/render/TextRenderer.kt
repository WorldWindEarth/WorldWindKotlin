package earth.worldwind.render

import earth.worldwind.render.image.ImageTexture
import earth.worldwind.shape.TextAttributes
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max

actual open class TextRenderer actual constructor(protected val rc: RenderContext) {
    /**
     * Creates a texture for a specified text string and specified text attributes.
     *
     * @param text The text string.
     * @returns A texture for the specified text string.
     */
    actual fun renderText(text: String?, attributes: TextAttributes): Texture? =
        if (text?.isNotEmpty() == true) ImageTexture(drawText(text, attributes)) else null

    private fun drawText(text: String, attributes: TextAttributes): BufferedImage {
        val lines = text.split("\n")
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeG = probe.createGraphics()
        probeG.font = attributes.font.font
        val fm = probeG.fontMetrics
        val maxLineWidth = lines.maxOfOrNull { fm.stringWidth(it) } ?: 0
        val lineHeight = max(1, fm.height)
        val textHeight = lineHeight * lines.size
        probeG.dispose()

        val outlinePadding = if (attributes.isOutlineEnabled) ceil(attributes.outlineWidth).toInt() else 0
        val width = max(1, maxLineWidth + outlinePadding * 2 + 2)
        val height = max(1, textHeight + outlinePadding * 2 + 2)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.font = attributes.font.font
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val x = (outlinePadding + 1).toFloat()
        var y = (outlinePadding + 1 + g.fontMetrics.ascent).toFloat()

        if (attributes.isOutlineEnabled) {
            g.stroke = BasicStroke(attributes.outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = Color(
                attributes.outlineColor.red.coerceIn(0f, 1f),
                attributes.outlineColor.green.coerceIn(0f, 1f),
                attributes.outlineColor.blue.coerceIn(0f, 1f),
                attributes.outlineColor.alpha.coerceIn(0f, 1f)
            )
            for (line in lines) {
                val shape = g.font.createGlyphVector(g.fontRenderContext, line).getOutline(x, y)
                g.draw(shape)
                y += lineHeight
            }
            y = (outlinePadding + 1 + g.fontMetrics.ascent).toFloat()
        }

        g.color = Color(
            attributes.textColor.red.coerceIn(0f, 1f),
            attributes.textColor.green.coerceIn(0f, 1f),
            attributes.textColor.blue.coerceIn(0f, 1f),
            attributes.textColor.alpha.coerceIn(0f, 1f)
        )
        for (line in lines) {
            g.drawString(line, x, y)
            y += lineHeight
        }
        g.dispose()

        return image
    }
}