package earth.worldwind.layer

import earth.worldwind.draw.DrawableTessellation
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.BasicShaderProgram

class ShowTessellationLayer: AbstractLayer("Terrain Tessellation") {
    override var isPickEnabled = false
    var color = Color()
        set(value) {
            field.copy(value)
        }

    override fun doRender(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) return  // no terrain to render

        // Use WorldWind's basic GLSL program.
        val program = rc.getShaderProgram { BasicShaderProgram() }
        val pool = rc.getDrawablePool<DrawableTessellation>()
        val drawable = DrawableTessellation.obtain(pool).set(program, color)
        rc.offerSurfaceDrawable(drawable, 1.0 /*z-order after surface textures*/)
    }
}