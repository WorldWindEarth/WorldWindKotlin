package earth.worldwind.layer.mbtiles

import earth.worldwind.geom.Location
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MBTilesLayerFactory {
    suspend fun createLayer(pathName: String, readOnly: Boolean = true) = withContext(Dispatchers.IO) {
        val tileFactory = MBTileFactory(pathName, readOnly)
        val tileOrigin = MercatorSector()
        val sector = MercatorSector().copy(tileFactory.boundingSector ?: tileOrigin)
        val levelSet = LevelSet(
            sector = sector,
            tileOrigin = tileOrigin,
            firstLevelDelta = Location(tileOrigin.deltaLatitude, tileOrigin.deltaLongitude),
            numLevels = tileFactory.maxZoom + 1,
            tileWidth = 256,
            tileHeight = 256,
            levelOffset = tileFactory.minZoom
        )
        MercatorTiledImageLayer(tileFactory.contentKey, MercatorTiledSurfaceImage(tileFactory, levelSet).apply {
            if (tileFactory.type != "overlay") imageOptions = ImageOptions(ImageConfig.RGB_565)
            cacheTileFactory = tileFactory
        })
    }
}