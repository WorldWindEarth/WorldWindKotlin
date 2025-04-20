package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable

expect open class MilStd2525TacticalGraphic(
    symbolID: String, locations: List<Location>, boundingSector: Sector = defaultBoundingSector(locations),
    modifiers: Map<String, String>? = null, attributes: Map<String, String>? = null
) : AbstractMilStd2525TacticalGraphic {
    override fun makeRenderables(rc: RenderContext, scale: Double): List<Renderable>
}