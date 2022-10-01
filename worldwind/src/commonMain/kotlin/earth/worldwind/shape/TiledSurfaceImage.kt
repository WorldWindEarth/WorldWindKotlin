package earth.worldwind.shape

import earth.worldwind.draw.DrawableSurfaceTexture
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Sector
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageTile
import earth.worldwind.render.program.SurfaceTextureProgram
import earth.worldwind.util.*
import kotlin.jvm.JvmOverloads

open class TiledSurfaceImage @JvmOverloads constructor(
    tileFactory: TileFactory, levelSet: LevelSet = LevelSet() // empty level set
): AbstractRenderable("Tiled Surface Image") {
    var tileFactory = tileFactory
        set(value) {
            field = value
            invalidateTiles()
        }
    var levelSet = levelSet
        set(value) {
            field = value
            invalidateTiles()
        }
    var imageOptions: ImageOptions? = null
        set(value) {
            field = value
            invalidateTiles()
        }
    var detailControl = 1.0
    var cacheTileFactory: TileFactory? = null
    var useCacheOnly = false
    protected val topLevelTiles = mutableListOf<Tile>()

    companion object {
        // Retrieve top level tiles to avoid black holes when navigating and zooming out camera
        private const val RETRIEVE_TOP_LEVEL_TILES = true
    }

    /**
     * Memory cache for this layer's subdivision tiles. Each entry contains an array of four image tiles corresponding
     * to the subdivision of the group's common parent tile. The cache is configured to hold 1000 groups, a number
     * empirically determined to be sufficient for storing the tiles needed to navigate a small region.
     */
    protected val tileCache = LruMemoryCache<String, Array<Tile>>(1000)
    protected var activeProgram: SurfaceTextureProgram? = null
    protected var ancestorTile: ImageTile? = null
    protected var ancestorTexture: Texture? = null
    protected val ancestorTexCoordMatrix = Matrix3()

    override fun doRender(rc: RenderContext) {
        if (rc.terrain!!.sector.isEmpty) return  // no terrain surface to render on
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
     * @param resolution the target resolution, provided in radians of latitude per texel.
     * @return List of tiles which fit specified sector and maximum resolution.
     */
    open fun assembleTilesList(sector: Sector, resolution: Double): List<ImageTile> {
        val result = mutableListOf<ImageTile>()
        val level = levelSet.levelForResolution(resolution)
        if (topLevelTiles.isEmpty()) createTopLevelTiles()
        topLevelTiles.forEach { addAndSubdivideTile(it as ImageTile, sector, level, result) }
        return result
    }

    protected open fun addAndSubdivideTile(tile: ImageTile, sector: Sector, level: Level?, result: MutableList<ImageTile>) {
        if (!tile.intersectsSector(sector)) return // Ignore tiles and its descendants outside the specified sector
        result.add(tile)
        if (tile.level != level) tile.subdivide(tileFactory).forEach {
            addAndSubdivideTile(it as ImageTile, sector, level, result)
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
        for (tile in topLevelTiles) addTileOrDescendants(rc, tile as ImageTile)
    }

    protected open fun createTopLevelTiles() {
        levelSet.firstLevel?.let { Tile.assembleTilesForLevel(it, tileFactory, topLevelTiles) }
    }

    protected open fun addTileOrDescendants(rc: RenderContext, tile: ImageTile) {
        // ignore the tile and its descendants if it's not needed or not visible
        if (!tile.intersectsSector(levelSet.sector) || !tile.intersectsFrustum(rc, rc.frustum)) return
        if (tile.level.isLastLevel || !tile.mustSubdivide(rc, detailControl)) {
            addTile(rc, tile)
            return  // use the tile if it does not need to be subdivided
        }
        val currentAncestorTile = ancestorTile
        val currentAncestorTexture = ancestorTexture
        getTexture(rc, tile, RETRIEVE_TOP_LEVEL_TILES)?.let { tileTexture ->
            // tile has a texture; use it as a fallback tile for descendants
            ancestorTile = tile
            ancestorTexture = tileTexture
        }
        // each tile has a cached size of 1, recursively process the tile's children
        for (child in tile.subdivideToCache(tileFactory, tileCache, 4)) addTileOrDescendants(rc, child as ImageTile)
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
        val isCacheAbsent = cacheSource == null || rc.renderResourceCache!!.absentResourceList.isResourceAbsent(cacheSource.hashCode())
        return rc.getTexture(
            if (isCacheAbsent) imageSource else cacheSource!!, imageOptions, retrieve && (!useCacheOnly || !isCacheAbsent)
        )
    }

    protected open fun invalidateTiles() {
        topLevelTiles.clear()
        tileCache.clear()
    }
}