package earth.worldwind.layer.mercator

import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.TileFactory

open class MercatorTiledSurfaceImage(tileFactory: TileFactory) : TiledSurfaceImage(tileFactory) {
    override fun createTopLevelTiles() {
        levelSet.firstLevel?.let { AbstractMercatorImageTile.assembleMercatorTilesForLevel(it, tileFactory, topLevelTiles) }
    }
}