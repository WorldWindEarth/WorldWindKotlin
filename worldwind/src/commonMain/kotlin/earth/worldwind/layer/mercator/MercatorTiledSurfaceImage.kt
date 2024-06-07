package earth.worldwind.layer.mercator

import earth.worldwind.layer.mercator.MercatorSector.Companion.fromSector
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.TileFactory

open class MercatorTiledSurfaceImage(tileFactory: TileFactory, levelSet: LevelSet) : TiledSurfaceImage(tileFactory, levelSet) {
    override fun clone() = MercatorTiledSurfaceImage(tileFactory, with(levelSet) {
        // It is crucial to keep the Mercator sector in the level set clone to keep correct tiling
        LevelSet(fromSector(sector), fromSector(tileOrigin), firstLevelDelta, numLevels, tileWidth, tileHeight, levelOffset)
    }).also {
        it.imageOptions = imageOptions
    }

    override fun createTopLevelTiles() =
        AbstractMercatorImageTile.assembleMercatorTilesForLevel(levelSet.firstLevel, tileFactory, topLevelTiles)
}