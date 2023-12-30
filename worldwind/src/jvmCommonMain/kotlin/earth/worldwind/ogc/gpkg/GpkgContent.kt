package earth.worldwind.ogc.gpkg

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class GpkgContent(
    override val container: AbstractGeoPackage,
    val tableName: String,
    val dataType: String,
    val identifier: String,
    var description: String = "",
    var lastChange: Instant = Clock.System.now(),
    var minX: Double? = null,
    var minY: Double? = null,
    var maxX: Double? = null,
    var maxY: Double? = null,
    val srsId: Int? = null
): GpkgEntry()