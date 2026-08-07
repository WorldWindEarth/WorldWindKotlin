@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.render

import earth.worldwind.geom.Vec2
import earth.worldwind.render.image.ImageData
import earth.worldwind.render.image.ImageTexture
import earth.worldwind.shape.TextAttributes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSAttributedString
import platform.Foundation.create
import platform.Foundation.NSNumber
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.NSStrokeColorAttributeName
import platform.UIKit.NSStrokeWidthAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.drawAtPoint
import platform.UIKit.size
import kotlin.math.ceil
import kotlin.math.max

/**
 * iOS [TextRenderer]. Lays text out as an `NSAttributedString` using the engine's [Font]
 * (mapped to a `UIFont`), draws each line into a tightly-packed RGBA8 `CGBitmapContext`
 * via UIKit's text drawing APIs, then wraps the resulting bytes in an [ImageTexture] for
 * GL upload.
 *
 * Multi-line text is split on `\n` and stacked vertically — same semantics as the
 * JVM/Android port. Outline rendering uses a two-pass approach when
 * `attributes.isOutlineEnabled`: an `NSStrokeColor` + positive `NSStrokeWidth` pass draws
 * the outlined shape underneath, then the regular `NSForegroundColor` pass paints the fill
 * on top. UIKit's `NSStrokeWidth` is documented as a percentage of font point size
 * (positive = stroke only, negative = stroke + fill); we convert
 * `outlineWidth_px → (outlineWidth / fontSize) * 100` to match the JVM's pixel-based
 * `BasicStroke` width semantics.
 */
actual open class TextRenderer actual constructor(protected val rc: RenderContext) {

    actual fun renderText(text: String?, attributes: TextAttributes): Texture? {
        if (text.isNullOrEmpty()) return null
        val data = drawText(text, attributes) ?: return null
        return ImageTexture(data)
    }

    /**
     * Render [text] into a fresh RGBA8 [ImageData]. Multi-line input is split on `\n` and
     * stacked vertically. The leading `+ 2` inner padding plus extra `outlinePadding`
     * match the Android/JVM padding so labels don't clip at the edges after sub-pixel
     * positioning by the placemark renderer.
     *
     * The bitmap is sized in *physical* pixels (logical text size × [RenderContext.densityFactor])
     * and the CG context is scaled so UIKit drawing in logical points fills the high-DPI
     * bitmap — same crispness UIKit gets when drawing through `UIGraphicsBeginImageContext-
     * WithOptions(scale)`. Without this, a 24-pt font on a @3x screen would land in just
     * 24 raw GL pixels (≈ 8 screen points) and look tiny.
     */
    protected open fun drawText(text: String, attributes: TextAttributes): ImageData? {
        val font = attributes.font
        val uiFont = toUIFont(font)
        val fillColor = uiColorOf(attributes.textColor)
        val outlineEnabled = attributes.isOutlineEnabled && attributes.outlineWidth > 0f

        // UIKit's NSStrokeWidth is "percent of font point size" — the same pixel-based
        // outline width the JVM uses converts to (px / fontSize) * 100.
        val strokeWidthPct = if (outlineEnabled) {
            (attributes.outlineWidth.toDouble() / uiFont.pointSize) * 100.0
        } else 0.0
        val outlinePadding = if (outlineEnabled) ceil(attributes.outlineWidth.toDouble()).toInt() else 0

        val fillAttrs = mapOf<Any?, Any>(
            NSFontAttributeName to uiFont,
            NSForegroundColorAttributeName to fillColor,
        )
        // Stroke pass: positive NSStrokeWidth = stroke only (no fill). We draw this
        // first; the fill pass then paints over the inside of the glyphs.
        val outlineAttrs: Map<Any?, Any>? = if (outlineEnabled) {
            mapOf(
                NSFontAttributeName to uiFont,
                NSStrokeColorAttributeName to uiColorOf(attributes.outlineColor),
                NSStrokeWidthAttributeName to NSNumber(double = strokeWidthPct),
            )
        } else null

        val lines = text.split("\n")
        if (lines.isEmpty()) return null
        val fillStrings = lines.map { NSAttributedString.create(string = it, attributes = fillAttrs) }
        val outlineStrings = outlineAttrs?.let { attrs ->
            lines.map { NSAttributedString.create(string = it, attributes = attrs) }
        }

        // Measure off the FILL strings — stroke widening doesn't change the layout, only
        // the visible glyph outline. (This mirrors the JVM impl which uses the unstroked
        // FontMetrics for advance widths.)
        var maxLineWidth = 0.0
        var totalHeight = 0.0
        val lineWidths = DoubleArray(lines.size)
        val lineHeights = DoubleArray(lines.size)
        for ((i, line) in fillStrings.withIndex()) {
            line.size().useContents {
                if (width > maxLineWidth) maxLineWidth = width
                lineWidths[i] = width
                lineHeights[i] = height
                totalHeight += height
            }
        }
        // Bitmap dims in *logical* points first; we'll multiply by density for the
        // physical-pixel allocation just below.
        val logicalW = max(1, ceil(maxLineWidth).toInt() + outlinePadding * 2 + 2)
        val logicalH = max(1, ceil(totalHeight).toInt() + outlinePadding * 2 + 2)
        // Ragged lines follow the anchor: offset fraction 0 = left, 0.5 = center, 1 = right.
        val align = (attributes.textOffset.offsetForSize(logicalW.toDouble(), logicalH.toDouble(), Vec2()).x / logicalW).coerceIn(0.0, 1.0)
        val density = rc.densityFactor.toDouble().coerceAtLeast(1.0)
        val w = max(1, ceil(logicalW * density).toInt())
        val h = max(1, ceil(logicalH * density).toInt())
        val rowBytes = w * 4
        val out = ByteArray(rowBytes * h)

        var success = false
        out.usePinned { pinned ->
            val bitmapInfo: UInt =
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big
            val ctx = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = w.toULong(),
                height = h.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = rowBytes.toULong(),
                space = sharedColorSpace,
                bitmapInfo = bitmapInfo,
            )
            if (ctx != null) {
                // CGBitmapContext defaults to a bottom-left origin and `UIGraphicsPushContext`
                // does not auto-flip. Translate by full height and scale Y by -density so
                // UIKit's drawAtPoint (which assumes top-left origin) lands the right way up.
                // Scaling X by +density keeps logical-point drawing crisp at @2x/@3x.
                CGContextTranslateCTM(ctx, 0.0, h.toDouble())
                CGContextScaleCTM(ctx, density, -density)
                UIGraphicsPushContext(ctx)
                val originX = (outlinePadding + 1).toDouble()
                val originY = (outlinePadding + 1).toDouble()

                // Outline pass first (if enabled), then fill on top.
                if (outlineStrings != null) {
                    var y = originY
                    for ((i, line) in outlineStrings.withIndex()) {
                        line.drawAtPoint(point = CGPointMake(originX + (maxLineWidth - lineWidths[i]) * align, y))
                        y += lineHeights[i]
                    }
                }
                var y = originY
                for ((i, line) in fillStrings.withIndex()) {
                    line.drawAtPoint(point = CGPointMake(originX + (maxLineWidth - lineWidths[i]) * align, y))
                    y += lineHeights[i]
                }
                UIGraphicsPopContext()
                CFRelease(ctx)
                success = true
            }
        }
        return if (success) ImageData(w, h, out) else null
    }

    private companion object {
        // Device-RGB color space is process-wide invariant; cache once instead of
        // create/release per drawText call (labels can re-render every frame).
        private val sharedColorSpace = CGColorSpaceCreateDeviceRGB()
    }

    private fun uiColorOf(color: Color): UIColor = UIColor.colorWithRed(
        red = sanitize(color.red),
        green = sanitize(color.green),
        blue = sanitize(color.blue),
        alpha = sanitize(color.alpha),
    )

    /** `Float.NaN.coerceIn(0f, 1f)` returns NaN — UIColor flags this with the
     *  "components far outside the expected range" runtime warning. Treat NaN/infinity
     *  as 0 (transparent black) so the warning never fires. */
    private fun sanitize(c: Float): Double {
        val d = c.toDouble()
        return if (d.isNaN() || d.isInfinite()) 0.0 else d.coerceIn(0.0, 1.0)
    }

    /** Map our engine [Font] to a `UIFont`. Falls back to system font when `family` is
     *  not installed on the device. */
    protected open fun toUIFont(font: Font): UIFont {
        val sz = font.size.toDouble()
        // Treat "default" / system family as the system font, with weight-aware variants.
        val systemFamily = font.family.equals("system", ignoreCase = true)
            || font.family.equals("default", ignoreCase = true)
            || font.family.equals("sans-serif", ignoreCase = true)
        return when {
            systemFamily -> when (font.weight) {
                FontWeight.BOLD -> UIFont.boldSystemFontOfSize(sz)
                FontWeight.ITALIC -> UIFont.italicSystemFontOfSize(sz)
                else -> UIFont.systemFontOfSize(sz)
            }
            else -> UIFont.fontWithName(font.family, sz) ?: UIFont.systemFontOfSize(sz)
        }
    }
}
