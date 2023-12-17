package earth.worldwind.layer.mercator

import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.TileFactory

open class MercatorTiledSurfaceImage(tileFactory: TileFactory, levelSet: LevelSet) : TiledSurfaceImage(tileFactory, levelSet) {
    override fun createTopLevelTiles() {
        AbstractMercatorImageTile.assembleMercatorTilesForLevel(levelSet.firstLevel, tileFactory, topLevelTiles)
    }
}