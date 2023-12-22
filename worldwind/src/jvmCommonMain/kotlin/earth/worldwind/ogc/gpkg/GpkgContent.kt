package earth.worldwind.ogc.gpkg

class GpkgContent(
    override val container: AbstractGeoPackage,
    val tableName: String,
    val dataType: String,
    val identifier: String,
    val description: String = "",
    val lastChange: String,
    val minX: Double?,
    val minY: Double?,
    val maxX: Double?,
    val maxY: Double?,
    val srsId: Int?
): GpkgEntry() {
    /**
     * Preserves this content from being modified
     * Content is read-only also in case of whole container is read-only
     */
    var isReadOnly = true
        get() = field || container.isReadOnly
}