package earth.worldwind.layer.mvt

import earth.worldwind.render.RenderContext

actual fun curvedGlyphAdvanceScale(rc: RenderContext): Float = rc.densityFactor.coerceAtLeast(1f)
