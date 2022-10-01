package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import kotlin.jvm.JvmOverloads

expect open class MilStd2525TacticalGraphic @JvmOverloads constructor(
    sidc: String, locations: List<Location>, boundingSector: Sector = defaultBoundingSector(locations),
    modifiers: Map<String, String>? = null, attributes: Map<String, String>? = null
) : AbstractMilStd2525TacticalGraphic