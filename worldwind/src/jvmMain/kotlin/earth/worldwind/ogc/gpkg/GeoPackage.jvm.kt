package earth.worldwind.ogc.gpkg

import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageManager
import mil.nga.geopackage.extension.coverage.GriddedCoverageDataType
import mil.nga.geopackage.extension.nga.style.FeatureStyleExtension
import mil.nga.geopackage.tiles.user.TileTableMetadata
import mil.nga.sf.Geometry
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

actual typealias CoverageData<TImage> = mil.nga.geopackage.extension.coverage.CoverageData<TImage>
actual typealias StyleRow = mil.nga.geopackage.extension.nga.style.StyleRow
actual typealias IconRow = mil.nga.geopackage.extension.nga.style.IconRow
actual typealias FeatureStyle = mil.nga.geopackage.extension.nga.style.FeatureStyle

actual fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore {
    var file = File(pathName)
    if (!isReadOnly && !file.exists()) file = GeoPackageManager.create(file, false)
    return GeoPackageManager.open(!isReadOnly, file)
}

actual fun createCoverageData(
    geoPackage: GeoPackageCore, tableName: String, identifier: String?, contentsBoundingBox: BoundingBox?,
    contentsSrsId: Long, tileBoundingBox: BoundingBox?, tileSrsId: Long, isFloat: Boolean,
): CoverageData<*> = CoverageData.createTileTable(
    geoPackage as GeoPackage,
    TileTableMetadata.create(tableName, contentsBoundingBox, contentsSrsId, tileBoundingBox, tileSrsId).also {
        it.identifier = identifier
    },
    if (isFloat) GriddedCoverageDataType.FLOAT else GriddedCoverageDataType.INTEGER
)

actual fun getFeatureList(geoPackage: GeoPackageCore, tableName: String): List<Pair<Geometry, FeatureStyle?>> {
    val featureStyleExtension = FeatureStyleExtension(geoPackage as GeoPackage)
    return geoPackage.getFeatureDao(tableName).queryForAll().mapNotNull { row ->
        row.getGeometry()?.geometry?.takeIf { !it.isEmpty }?.let { geometry ->
            geometry to featureStyleExtension.getFeatureStyle(tableName, row.id, geometry.geometryType)
        }
    }
}

actual fun buildImageSource(iconRow: IconRow) = ImageSource.fromImage(iconRow.dataImage.let { image ->
    val width = iconRow.width?.roundToInt() ?: image.width
    val height = iconRow.height?.roundToInt() ?: image.height
    if (width != image.width || height != image.height) {
        val scaledImage = image.getScaledInstance(width, height, Image.SCALE_SMOOTH)
        BufferedImage(width, height, image.type).apply {
            createGraphics().apply {
                drawImage(scaledImage, 0, 0, null)
                dispose()
            }
        }
    } else image
})