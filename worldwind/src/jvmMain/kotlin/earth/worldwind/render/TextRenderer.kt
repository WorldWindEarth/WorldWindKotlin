package earth.worldwind.render

import earth.worldwind.geom.Vec2
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
        val density = rc.densityFactor.coerceAtLeast(1f)
        val lines = text.split("\n")
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeG = probe.createGraphics()
        probeG.font = attributes.font.font
        val fm = probeG.fontMetrics
        val lineWidths = IntArray(lines.size) { fm.stringWidth(lines[it]) }
        val maxLineWidth = lineWidths.maxOrNull() ?: 0
        val lineHeight = max(1, fm.height)
        val textHeight = lineHeight * lines.size
        probeG.dispose()

        val outlinePadding = if (attributes.isOutlineEnabled) ceil(attributes.outlineWidth).toInt() else 0
        val logicalW = max(1, maxLineWidth + outlinePadding * 2 + 2)
        val logicalH = max(1, textHeight + outlinePadding * 2 + 2)
        val width = ceil(logicalW * density).toInt()
        val height = ceil(logicalH * density).toInt()
        // Ragged lines follow the anchor: offset fraction 0 = left, 0.5 = center, 1 = right.
        val align = (attributes.textOffset.offsetForSize(logicalW.toDouble(), logicalH.toDouble(), Vec2()).x / logicalW).coerceIn(0.0, 1.0)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(density.toDouble(), density.toDouble())
        g.font = attributes.font.font
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val x0 = (outlinePadding + 1).toFloat()
        var y = (outlinePadding + 1 + g.fontMetrics.ascent).toFloat()

        if (attributes.isOutlineEnabled) {
            g.stroke = BasicStroke(attributes.outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = Color(
                attributes.outlineColor.red.coerceIn(0f, 1f),
                attributes.outlineColor.green.coerceIn(0f, 1f),
                attributes.outlineColor.blue.coerceIn(0f, 1f),
                attributes.outlineColor.alpha.coerceIn(0f, 1f)
            )
            for (i in lines.indices) {
                val x = x0 + ((maxLineWidth - lineWidths[i]) * align).toFloat()
                val shape = g.font.createGlyphVector(g.fontRenderContext, lines[i]).getOutline(x, y)
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
        for (i in lines.indices) {
            g.drawString(lines[i], x0 + ((maxLineWidth - lineWidths[i]) * align).toFloat(), y)
            y += lineHeight
        }
        g.dispose()

        return image
    }
}