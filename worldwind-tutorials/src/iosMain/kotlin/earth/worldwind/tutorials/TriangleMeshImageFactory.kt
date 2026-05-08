@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.tutorials

import earth.worldwind.render.image.ImageSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGContextAddArc
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextClip
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawRadialGradient
import platform.CoreGraphics.CGGradientCreateWithColorComponents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage

/** iOS port of the JVM/Android [TriangleMeshImageFactory]: red->green->red radial gradient
 *  filling a circle of [outerRadius]; outside the circle stays transparent. [innerRadius]
 *  is unused, matching the JVM/Android impls. */
actual class TriangleMeshImageFactory actual constructor(
    private val size: Int,
    @Suppress("UNUSED_PARAMETER") innerRadius: Float,
    private val outerRadius: Float,
) : ImageSource.ImageFactory {
    override suspend fun createImage(): UIImage? {
        val c = (size / 2.0) - 0.5

        UIGraphicsBeginImageContextWithOptions(
            size = CGSizeMake(size.toDouble(), size.toDouble()),
            opaque = false,
            scale = 1.0
        )
        val ctx = UIGraphicsGetCurrentContext() ?: run {
            UIGraphicsEndImageContext()
            return null
        }

        val components = cValuesOf(
            1.0, 0.0, 0.0, 1.0, // red at center
            0.0, 1.0, 0.0, 1.0, // green at midpoint
            1.0, 0.0, 0.0, 1.0, // red at outer edge
        )
        val locations = cValuesOf(0.0, 0.5, 1.0)

        val cs = CGColorSpaceCreateDeviceRGB()
        val gradient = CGGradientCreateWithColorComponents(cs, components, locations, 3u)
        if (gradient != null && cs != null) {
            // Clip to the outer circle so the gradient doesn't paint the whole bitmap.
            CGContextBeginPath(ctx)
            CGContextAddArc(ctx, c, c, outerRadius.toDouble(), 0.0, 2.0 * kotlin.math.PI, 0)
            CGContextClip(ctx)
            CGContextDrawRadialGradient(
                ctx,
                gradient,
                CGPointMake(c, c), 0.0,
                CGPointMake(c, c), outerRadius.toDouble(),
                0u
            )
            CFRelease(gradient)
        }
        if (cs != null) CFRelease(cs)

        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return image
    }
}
