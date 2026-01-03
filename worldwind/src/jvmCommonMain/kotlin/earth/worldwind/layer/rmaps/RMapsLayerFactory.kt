package earth.worldwind.layer.rmaps

import earth.worldwind.geom.Location
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RMapsLayerFactory {
    suspend fun createLayer(
        pathName: String, readOnly: Boolean = true, imageFormat: String? = null
    ) = withContext(Dispatchers.IO) {
        val tileFactory = RMapsTileFactory(pathName, readOnly, imageFormat)
        val tileOrigin = MercatorSector()
        val sector = MercatorSector()
        val levelSet = LevelSet(
            sector = sector,
            tileOrigin = tileOrigin,
            firstLevelDelta = Location(tileOrigin.deltaLatitude, tileOrigin.deltaLongitude),
            numLevels = tileFactory.maxZoom + 1,
            tileWidth = 256,
            tileHeight = 256,
            levelOffset = tileFactory.minZoom
        )
        MercatorTiledImageLayer(tileFactory.contentKey, MercatorTiledSurfaceImage(tileFactory, levelSet)).apply {
            tiledSurfaceImage?.cacheTileFactory = tileFactory
        }
    }
}