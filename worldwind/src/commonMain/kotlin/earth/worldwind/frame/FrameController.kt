package earth.worldwind.frame

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.RenderContext

interface FrameController {
    fun renderFrame(rc: RenderContext)
    fun drawFrame(dc: DrawContext)
}