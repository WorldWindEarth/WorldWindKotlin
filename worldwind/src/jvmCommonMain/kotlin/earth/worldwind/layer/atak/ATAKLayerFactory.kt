package earth.worldwind.layer.atak

import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.atak.ATAKTileFactory.Companion.EPSG_3857
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ATAKLayerFactory {
    suspend fun createLayer(
        pathName: String, readOnly: Boolean = true, imageFormat: String? = null
    ) = withContext(Dispatchers.IO) {
        val tileFactory = ATAKTileFactory(pathName, readOnly, imageFormat)
        val srid = tileFactory.srid
        val tileOrigin = if (srid == EPSG_3857) MercatorSector() else Sector().setFullSphere()
        val sector = if (srid == EPSG_3857) MercatorSector() else Sector().setFullSphere()
        val levelSet = LevelSet(
            sector = sector,
            tileOrigin = tileOrigin,
            firstLevelDelta = Location(tileOrigin.deltaLatitude, tileOrigin.deltaLongitude),
            numLevels = 20, // Maximal level is unknown from the source file
            tileWidth = 256,
            tileHeight = 256
        )
        if (srid == EPSG_3857) {
            MercatorTiledImageLayer(tileFactory.contentKey, MercatorTiledSurfaceImage(tileFactory, levelSet))
        } else {
            TiledImageLayer(tileFactory.contentKey, TiledSurfaceImage(tileFactory, levelSet))
        }.apply { tiledSurfaceImage?.cacheTileFactory = tileFactory }
    }
}