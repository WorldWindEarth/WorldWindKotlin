package earth.worldwind.shape

import earth.worldwind.draw.DrawableSurfaceTexture
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Sector
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageTile
import earth.worldwind.render.program.SurfaceTextureProgram
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory

open class TiledSurfaceImage(tileFactory: TileFactory, levelSet: LevelSet): AbstractRenderable("Tiled Surface Image") {
    /**
     * Tile factory implementation.
     */
    var tileFactory = tileFactory
        set(value) {
            field = value
            invalidateTiles()
        }
    /**
     * Tile pyramid representation.
     */
    var levelSet = levelSet
        set(value) {
            field = value
            invalidateTiles()
        }
    /**
     * Additional image texture options.
     */
    var imageOptions: ImageOptions? = null
        set(value) {
            field = value
            invalidateTiles()
        }
    /**
     * Define imagery level of details. It controls tile pixel density on the screen.
     */
    var detailControl = 1.0
    /**
     * Determines how many levels to skip from retrieving texture during tile pyramid subdivision.
     */
    var levelOffset = 0
    /**
     * Define cache tiles factory implementation.
     */
    var cacheTileFactory: TileFactory? = null
    /**
     * Configures tiled surface image to work only with cache source.
     */
    var useCacheOnly = false
    protected val topLevelTiles = mutableListOf<Tile>()

    companion object {
        // Retrieve top level tiles to avoid black holes when navigating and zooming out camera
        private const val RETRIEVE_TOP_LEVEL_TILES = true
    }

    /**
     * Memory cache for this layer's subdivision tiles. Each entry contains an array of four image tiles corresponding
     * to the subdivision of the group's common parent tile. The cache is configured to hold 1200 groups, a number
     * empirically determined to be sufficient for storing the tiles needed to navigate a small region.
     */
    protected var tileCache = LruMemoryCache<String, Array<Tile>>(1200)
    protected var activeProgram: SurfaceTextureProgram? = null
    protected var ancestorTile: ImageTile? = null
    protected var ancestorTexture: Texture? = null
    protected val ancestorTexCoordMatrix = Matrix3()

    /**
     * Cache size should be adjusted in case of levelSet or detailControl changed.
     */
    fun setupTileCache(capacity: Long, lowWater: Long = (capacity * 0.75).toLong()) {
        tileCache = LruMemoryCache(capacity, lowWater)
    }

    override fun doRender(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) return  // no terrain surface to render on
        determineActiveProgram(rc)
        assembleTiles(rc)
        activeProgram = null // clear the active program to avoid leaking render resources
        ancestorTile = null // clear the ancestor tile and texture
        ancestorTexture = null
    }

    /**
     * Determine list of tiles which fit specified sector and maximum resolution.
     *
     * @param sector     the bounding sector.
     * @param resolution the desired resolution in angular value of latitude per pixel.
     * @return List of tiles which fit specified sector and maximum resolution.
     */
    open fun assembleTilesList(sector: Sector, resolution: Angle): List<ImageTile> {
        val result = mutableListOf<ImageTile>()
        val lastLevelNumber = levelSet.levelForResolution(resolution).levelNumber
        if (topLevelTiles.isEmpty()) createTopLevelTiles()
        topLevelTiles.forEach { addAndSubdivideTile(it as ImageTile, sector, lastLevelNumber, result) }
        return result
    }

    protected open fun addAndSubdivideTile(tile: ImageTile, sector: Sector, lastLevelNumber: Int, result: MutableList<ImageTile>) {
        if (!tile.intersectsSector(sector)) return // Ignore tiles and its descendants outside the specified sector
        // Skip tiles with level less than specified offset from the result list
        if (tile.level.levelNumber >= levelOffset) result.add(tile)
        // Do not subdivide if specified level or last available level reached
        if (tile.level.levelNumber < lastLevelNumber && !tile.level.isLastLevel) {
            tile.subdivide(tileFactory).forEach {
                addAndSubdivideTile(it as ImageTile, sector, lastLevelNumber, result)
            }
        }
    }

    protected open fun determineActiveProgram(rc: RenderContext) {
        activeProgram = rc.getShaderProgram { SurfaceTextureProgram() }
    }

    protected open fun assembleTiles(rc: RenderContext) {
        // TODO
        // The need to create Tiles with a defined image source couples the need to determine a tile's visibility with
        // he need to know its image source. Decoupling the two would mean we only need to know the image source when
        // the texture is actually requested Could the tile-based operations done here be implicit on level/row/column,
        // or use transient pooled tile objects not tied to an image source?
        if (topLevelTiles.isEmpty()) createTopLevelTiles()
        for (i in topLevelTiles.indices) addTileOrDescendants(rc, topLevelTiles[i] as ImageTile)
    }

    protected open fun createTopLevelTiles() {
        levelSet.firstLevel?.let { Tile.assembleTilesForLevel(it, tileFactory, topLevelTiles) }
    }

    protected open fun addTileOrDescendants(rc: RenderContext, tile: ImageTile) {
        // ignore the tile and its descendants if it's not needed or not visible
        if (!tile.intersectsSector(levelSet.sector) || !rc.terrain.intersects(tile.sector)) return
        val retrieveCurrentLevel = tile.level.levelNumber >= levelOffset
        if (tile.level.isLastLevel || !tile.mustSubdivide(rc, detailControl)) {
            if (retrieveCurrentLevel) addTile(rc, tile)
            return  // use the tile if it does not need to be subdivided
        }
        val currentAncestorTile = ancestorTile
        val currentAncestorTexture = ancestorTexture
        getTexture(rc, tile, RETRIEVE_TOP_LEVEL_TILES && retrieveCurrentLevel)?.let { tileTexture ->
            // tile has a texture; use it as a fallback tile for descendants
            ancestorTile = tile
            ancestorTexture = tileTexture
        }
        // each tile has a cached size of 1, recursively process the tile's children
        val children = tile.subdivideToCache(tileFactory, tileCache, 4)
        for (i in children.indices) addTileOrDescendants(rc, children[i] as ImageTile)
        ancestorTile = currentAncestorTile // restore the last fallback tile, even if it was null
        ancestorTexture = currentAncestorTexture
    }

    protected open fun addTile(rc: RenderContext, tile: ImageTile) {
        val texture = getTexture(rc, tile)
        val ancestorTile = ancestorTile
        val ancestorTexture = ancestorTexture
        if (texture != null) { // use the tile's own texture
            val pool = rc.getDrawablePool<DrawableSurfaceTexture>()
            val drawable = DrawableSurfaceTexture.obtain(pool).set(activeProgram, tile.sector, texture, texture.coordTransform)
            rc.offerSurfaceDrawable(drawable, 0.0 /*z-order*/)
        } else if (ancestorTile != null && ancestorTexture != null) { // use the ancestor tile's texture, transformed to fill the tile sector
            ancestorTexCoordMatrix.copy(ancestorTexture.coordTransform)
            ancestorTexCoordMatrix.multiplyByTileTransform(tile.sector, ancestorTile.sector)
            val pool = rc.getDrawablePool<DrawableSurfaceTexture>()
            val drawable = DrawableSurfaceTexture.obtain(pool).set(activeProgram, tile.sector, ancestorTexture, ancestorTexCoordMatrix)
            rc.offerSurfaceDrawable(drawable, 0.0 /*z-order*/)
        }
    }

    // TODO If cache source retrieved but it is outdated, than try to retrieve original image source anyway to refresh cache
    protected open fun getTexture(rc: RenderContext, tile: ImageTile, retrieve: Boolean = true): Texture? {
        // No image source indicates an empty level or an image missing from the tiled data store
        val imageSource = tile.imageSource ?: return null
        // If cache tile factory is specified, then create cache source and store it in tile
        val cacheSource = tile.cacheSource ?: cacheTileFactory?.run {
            (createTile(tile.sector, tile.level, tile.row, tile.column) as ImageTile).imageSource?.also { tile.cacheSource = it }
        }
        // If cache source is not absent, then retrieve it instead of original image source
        val isCacheAbsent = cacheSource == null || rc.renderResourceCache.absentResourceList.isResourceAbsent(cacheSource.hashCode())
        return rc.getTexture(
            if (isCacheAbsent) imageSource else cacheSource!!, imageOptions, retrieve && (!useCacheOnly || !isCacheAbsent)
        )
    }

    protected open fun invalidateTiles() {
        topLevelTiles.clear()
        tileCache.clear()
    }
}