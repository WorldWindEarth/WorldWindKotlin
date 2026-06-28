package earth.worldwind.layer.mvt

import earth.worldwind.render.RenderContext

// Web's TextRenderer rasterises glyph bitmaps at densityFactor and the viewport is in device
// pixels, while Font.measureChars returns logical (CSS) px advances — same split as JVM/iOS.
// Scale advances up to match, else glyphs overlap by devicePixelRatio (≈2× on Retina).
actual fun curvedGlyphAdvanceScale(rc: RenderContext): Float = rc.densityFactor.coerceAtLeast(1f)
