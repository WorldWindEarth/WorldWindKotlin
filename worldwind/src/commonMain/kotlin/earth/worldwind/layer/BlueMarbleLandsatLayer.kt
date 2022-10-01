package earth.worldwind.layer

import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Sector
import earth.worldwind.ogc.WmsLayerConfig
import earth.worldwind.ogc.WmsTileFactory
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.*
import kotlin.jvm.JvmOverloads

/**
 * Displays a composite of NASA's Blue Marble next generation imagery at 500m resolution and Landsat imagery at 15m
 * resolution from an OGC Web Map Service (WMS). By default, BlueMarbleLandsatLayer is configured to retrieve imagery
 * from the WMS at [&amp;https://worldwind25.arc.nasa.gov/wms](https://worldwind25.arc.nasa.gov/wms?SERVICE=WMS&amp;REQUEST=GetCapabilities).
 */
class BlueMarbleLandsatLayer @JvmOverloads constructor(
    serviceAddress: String = "https://worldwind25.arc.nasa.gov/wms"
): TiledImageLayer("Blue Marble & Landsat"), TileFactory {
    override var tiledSurfaceImage: TiledSurfaceImage? = TiledSurfaceImage(this, LevelSet(LevelSetConfig().apply {
        // Configure this layer's level set to capture the entire globe at 15m resolution.
        numLevels = numLevelsForResolution(15.0 / Ellipsoid.WGS84.semiMajorAxis)
    })).apply {
        // Reduce memory usage by using a 16-bit configuration with no alpha
        imageOptions = ImageOptions(ImageConfig.RGB_565)
    }.also { addRenderable(it) }

    private val blueMarbleTileFactory = WmsTileFactory(WmsLayerConfig(serviceAddress, "BlueMarble-200405").apply {
        isTransparent = false // the BlueMarble layer is opaque
    })

    private val landsatTileFactory = WmsTileFactory(WmsLayerConfig(serviceAddress, "BlueMarble-200405,esat").apply {
        isTransparent = false // combining BlueMarble and esat layers results in opaque images
    })

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile {
        val radiansPerPixel = level.tileDelta.latitude.radians / level.tileHeight
        val metersPerPixel = radiansPerPixel * Ellipsoid.WGS84.semiMajorAxis
        // switch to Landsat at 2km resolution
        return if (metersPerPixel < 2.0e3) landsatTileFactory.createTile(sector, level, row, column)
        else blueMarbleTileFactory.createTile(sector, level, row, column)
    }
}