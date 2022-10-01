package earth.worldwind.frame

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.RenderContext

interface FrameMetrics {
    fun beginRendering(rc: RenderContext)
    fun endRendering(rc: RenderContext)
    fun beginDrawing(dc: DrawContext)
    fun endDrawing(dc: DrawContext)
    fun reset()
}