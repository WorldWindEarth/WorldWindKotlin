package earth.worldwind.layer.mvt

import earth.worldwind.render.RenderContext

/** Factor converting [earth.worldwind.render.Font.measureChars] advances (logical pixels) into the
 *  units of the rendered glyph bitmaps and the projected screen polyline. On platforms whose
 *  TextRenderer density-scales the glyph bitmap and projects into a device-pixel viewport (JVM,
 *  iOS, web) this is [RenderContext.densityFactor]; on platforms that rasterise text at logical
 *  size (Android) it is 1. */
expect fun curvedGlyphAdvanceScale(rc: RenderContext): Float
