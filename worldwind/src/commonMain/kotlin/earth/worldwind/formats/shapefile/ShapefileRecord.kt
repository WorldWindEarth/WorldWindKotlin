package earth.worldwind.formats.shapefile

/**
 * Shape type discriminator for a shapefile record. ESRI shapefiles carry the type code in
 * the file header and again at the start of every record's contents.
 *
 * Constants and groupings mirror WebWorldWind's `Shapefile.*` constants. MultiPatch (31)
 * is intentionally omitted to match the reference implementation.
 */
enum class ShapefileShapeType(val code: Int) {
    NULL(0),
    POINT(1),
    POLYLINE(3),
    POLYGON(5),
    MULTI_POINT(8),
    POINT_Z(11),
    POLYLINE_Z(13),
    POLYGON_Z(15),
    MULTI_POINT_Z(18),
    POINT_M(21),
    POLYLINE_M(23),
    POLYGON_M(25),
    MULTI_POINT_M(28);

    val isPoint: Boolean get() = this == POINT || this == POINT_Z || this == POINT_M
    val isMultiPoint: Boolean get() = this == MULTI_POINT || this == MULTI_POINT_Z || this == MULTI_POINT_M
    val isPolyline: Boolean get() = this == POLYLINE || this == POLYLINE_Z || this == POLYLINE_M
    val isPolygon: Boolean get() = this == POLYGON || this == POLYGON_Z || this == POLYGON_M
    val isZ: Boolean get() = this == POINT_Z || this == MULTI_POINT_Z || this == POLYLINE_Z || this == POLYGON_Z
    /** Z types implicitly carry optional measures; pure M types always do. */
    val isMeasure: Boolean get() = isZ ||
            this == POINT_M || this == MULTI_POINT_M || this == POLYLINE_M || this == POLYGON_M

    companion object {
        fun fromCode(code: Int): ShapefileShapeType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One parsed shapefile record. Records of all shape types are represented by this single
 * class; the [shapeType] field discriminates. Geometry is exposed as a list of "parts",
 * each part an interleaved `[x0, y0, x1, y1, …]` `DoubleArray` matching the on-disk
 * coordinate order (longitude, latitude for geographic shapefiles).
 *
 * @property shapeType Shape type as declared by the file header.
 * @property recordNumber One-based ordinal position in the .shp file.
 * @property parts Per-part geometry. Empty for [ShapefileShapeType.NULL] records.
 * @property boundingRectangle `[minY, maxY, minX, maxX]`, or `null` for Null records.
 * @property zRange `[zMin, zMax]` when [ShapefileShapeType.isZ]; otherwise `null`.
 * @property zValues One Z value per point when [ShapefileShapeType.isZ]; otherwise `null`.
 * @property mRange `[mMin, mMax]` when the file carries measures and they were present.
 * @property mValues One M value per point when present.
 * @property attributes Attributes from the sidecar DBF, keyed by column name. Empty when no
 *     DBF is provided or when the row was deleted in the source.
 */
class ShapefileRecord internal constructor(
    val shapeType: ShapefileShapeType,
    val recordNumber: Int,
    val parts: List<DoubleArray>,
    val boundingRectangle: DoubleArray?,
    val zRange: DoubleArray? = null,
    val zValues: DoubleArray? = null,
    val mRange: DoubleArray? = null,
    val mValues: DoubleArray? = null,
) {
    val numberOfParts: Int get() = parts.size
    val numberOfPoints: Int get() = parts.sumOf { it.size / 2 }
    val firstPartNumber: Int get() = 0
    val lastPartNumber: Int get() = numberOfParts - 1
    val isNullRecord: Boolean get() = shapeType == ShapefileShapeType.NULL

    var attributes: Map<String, Any?> = emptyMap()
        internal set

    val isPointType: Boolean get() = shapeType.isPoint
    val isMultiPointType: Boolean get() = shapeType.isMultiPoint
    val isPolylineType: Boolean get() = shapeType.isPolyline
    val isPolygonType: Boolean get() = shapeType.isPolygon

    /**
     * Returns the points of the requested part as an interleaved `[x0, y0, x1, y1, …]`
     * `DoubleArray`, or `null` if [partNumber] is out of range.
     */
    fun pointBuffer(partNumber: Int): DoubleArray? =
        if (partNumber in parts.indices) parts[partNumber] else null
}
