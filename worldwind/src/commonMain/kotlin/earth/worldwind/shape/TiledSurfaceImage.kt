package earth.worldwind.shape

import earth.worldwind.draw.DrawableSurfaceTexture
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.layer.cache.TileSourceFactoryAdapter
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageTile
import earth.worldwind.render.program.SurfaceTextureProgram
import earth.worldwind.util.*

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
     * Retrieve top level tiles to avoid black holes when navigating and zooming out camera
     */
    var retrieveTopLevelTiles = true
    /**
     * Use ancestor tile texture as a fallback for descendants
     */
    var useAncestorTileTexture = true
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
    protected val topLevelTiles = mutableListOf<Tile>()
    protected var lastGlobeState: Globe.State? = null

    /**
     * Makes a copy of this tiled surface image
     */
    open fun clone() = TiledSurfaceImage(tileFactory, LevelSet(levelSet)).also {
        it.imageOptions = imageOptions
    }

    /**
     * Cache size should be adjusted in case of levelSet or detailControl changed.
     */
    fun setupTileCache(capacity: Long, lowWater: Long = (capacity * 0.75).toLong()) {
        tileCache = LruMemoryCache(capacity, lowWater)
    }

    override fun doRender(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) return // no terrain surface to render on
        // Stale-while-revalidate: drop textures for tiles a background refresh just re-cached so
        // they re-decode from the now-fresh store. Done here (render thread) because the texture
        // cache isn't synchronized; the refresh thread only enqueues + requests this redraw.
        (tileFactory as? TileSourceFactoryAdapter)?.drainRevalidatedImageSources()?.forEach {
            rc.renderResourceCache.remove(it)
        }
        checkGlobeState(rc)
        determineActiveProgram(rc)
        assembleTiles(rc)
        activeProgram = null // clear the active program to avoid leaking render resources
        ancestorTile = null // clear the ancestor tile and texture
        ancestorTexture = null
    }

    protected open suspend fun processAndSubdivideTile(
        tile: ImageTile, sector: Sector, minLevel: Level, maxLevel: Level, process: suspend (ImageTile) -> Unit
    ) {
        if (!tile.intersectsSector(sector)) return // Ignore tiles and its descendants outside the specified sector
        val levelNumber = tile.level.levelNumber
        // Skip tiles with level less than specified offset from the process
        if (levelNumber >= minLevel.levelNumber && levelNumber >= levelSet.levelOffset) process(tile)
        // Do not subdivide if specified level or last available level reached
        if (levelNumber < maxLevel.levelNumber && !tile.level.isLastLevel) {
            tile.subdivide(tileFactory).forEach { processAndSubdivideTile(it as ImageTile, sector, minLevel, maxLevel, process) }
        }
    }

    protected open fun determineActiveProgram(rc: RenderContext) {
        activeProgram = SurfaceTextureProgram.get(rc)
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

    protected open fun createTopLevelTiles() = Tile.assembleTilesForLevel(levelSet.firstLevel, tileFactory, topLevelTiles)

    protected open fun addTileOrDescendants(rc: RenderContext, tile: ImageTile) {
        // ignore tiles which do not fit projection limits
        if (rc.globe.projectionLimits?.let { tile.intersectsSector(it) } == false) return
        // ignore the tile and its descendants if it's not needed, not visible or fully fogged
        if (!tile.intersectsSector(levelSet.sector) || !tile.intersectsSector(rc.terrain.sector) || !tile.intersectsFrustum(rc) || tile.isFullyFogged(rc)) return
        // Do not retrieve tiles bigger than level size because they can be simulated by downscaling more detailed tiles
        // TODO Remove this restriction when GeoPackage will be able to correctly align tiles bigger than level size
        val validSize = tile.level.levelWidth >= tile.level.tileWidth && tile.level.levelHeight >= tile.level.tileHeight
        // Do not retrieve tiles from levels before level offset
        val retrieveCurrentLevel = validSize && tile.level.levelNumber >= levelSet.levelOffset
        // Stop subdivide to cache if it is the last level or tile matches detail control
        val isLastLevel = tile.level.isLastLevel
        val mustSubdivide = tile.mustSubdivide(rc, detailControl)
        if (isLastLevel || !mustSubdivide) {
            // Skip using last level tile on more detailed levels if using ancestor tiles is switched off
            if (retrieveCurrentLevel && (!isLastLevel || !mustSubdivide || useAncestorTileTexture)) addTile(rc, tile)
            return  // use the tile if it does not need to be subdivided
        }
        val currentAncestorTile = ancestorTile
        val currentAncestorTexture = ancestorTexture
        getTexture(rc, tile, retrieveTopLevelTiles && retrieveCurrentLevel)?.let { tileTexture ->
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
        val imageSource = tile.imageSource
        val absentResourceList = rc.renderResourceCache.absentResourceList
        val opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        if (texture != null) {
            // use the tile's own texture
            val pool = rc.getDrawablePool(DrawableSurfaceTexture.KEY)
            val drawable = DrawableSurfaceTexture.obtain(pool).set(
                activeProgram, tile.sector, opacity, texture, texture.coordTransform, rc.globe.offset
            )
            rc.offerSurfaceDrawable(drawable, zOrder)
        } else if (ancestorTile != null && ancestorTexture != null && (
            // Use ancestor tile if it is allowed or previous level tile is still loading
            useAncestorTileTexture || tile.level.levelNumber - ancestorTile.level.levelNumber <= 1
                    && imageSource != null && !absentResourceList.isResourceAbsent(imageSource.hashCode())
        )) {
            // use the ancestor tile's texture, transformed to fill the tile sector
            ancestorTexCoordMatrix.copy(ancestorTexture.coordTransform)
            ancestorTexCoordMatrix.multiplyByTileTransform(tile.sector, ancestorTile.sector)
            val pool = rc.getDrawablePool(DrawableSurfaceTexture.KEY)
            val drawable = DrawableSurfaceTexture.obtain(pool).set(
                activeProgram, tile.sector, opacity, ancestorTexture, ancestorTexCoordMatrix, rc.globe.offset
            )
            rc.offerSurfaceDrawable(drawable, zOrder)
        }
    }

    protected open fun getTexture(rc: RenderContext, tile: ImageTile, retrieve: Boolean = true): Texture? {
        // No image source indicates an empty level or an image missing from the tiled data store.
        // Cache integration lives in the TileSource decorator (CachedTileSource) — this layer
        // no longer juggles a separate cacheTileFactory.
        val imageSource = tile.imageSource ?: return null
        return rc.getTexture(imageSource, imageOptions, retrieve)
    }

    protected open fun checkGlobeState(rc: RenderContext) {
        // Invalidate tiles cache when globe state changes
        if (rc.globeState != lastGlobeState) {
            invalidateTiles()
            lastGlobeState = rc.globeState
        }
    }

    open fun invalidateTiles() {
        topLevelTiles.clear()
        tileCache.clear()
    }

}