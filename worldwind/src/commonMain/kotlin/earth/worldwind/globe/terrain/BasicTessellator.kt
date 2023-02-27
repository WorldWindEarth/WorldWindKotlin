package earth.worldwind.globe.terrain

import earth.worldwind.draw.BasicDrawableTerrain
import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Location
import earth.worldwind.geom.Range
import earth.worldwind.geom.Sector
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.ShortBufferObject
import earth.worldwind.util.*
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER

open class BasicTessellator: Tessellator, TileFactory {
    override val lastTerrain = BasicTerrain()
    /**
     * Default level set is configured to ~10 meter resolution
     */
    var levelSet = LevelSet(Sector().setFullSphere(), Location(NEG90, NEG180), Location(POS90, POS90), 16, 32, 32)
        set(value) {
            field = value
            invalidateTiles()
        }
    /**
     * Detail control determines how much times terrain texel is greater than screen pixel.
     */
    var detailControl = 20.0
    /**
     * Memory cache for this tessellator's subdivision tiles. Each entry contains an array of four terrain tiles
     * corresponding to the subdivision of the group's common parent tile. The cache is configured to hold 320 groups, a
     * number tuned to store the tiles needed to navigate a small region, given the tessellator's first level tile delta
     * of 90 degrees, tile dimensions of 32x32 and detail control of 20.
     */
    protected var tileCache = LruMemoryCache<String, Array<Tile>>(400, 320)
    protected val topLevelTiles = mutableListOf<Tile>()
    protected val currentTerrain = BasicTerrain()
    protected var levelSetVertexTexCoords: FloatArray? = null
    protected var levelSetLineElements: ShortArray? = null
    protected var levelSetTriStripElements: ShortArray? = null
    protected val levelSetLineElementRange = Range()
    protected val levelSetTriStripElementRange = Range()
    protected var levelSetVertexTexCoordBuffer: FloatBufferObject? = null
    protected var levelSetElementBuffer: ShortBufferObject? = null
    protected var levelSetVertexTexCoordKey = this::class.simpleName + ".vertexTexCoordKey"
    protected var levelSetElementKey = this::class.simpleName + ".elementKey"

    /**
     * Cache size should be adjusted in case of levelSet or detailControl changed.
     */
    fun setupTileCache(capacity: Long, lowWater: Long = (capacity * 0.8).toLong()) {
        tileCache = LruMemoryCache(capacity, lowWater)
    }

    override fun tessellate(rc: RenderContext) {
        assembleTiles(rc)
        rc.terrain = currentTerrain
        if (!rc.isPickMode) lastTerrain.copy(currentTerrain)
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) = TerrainTile(sector, level, row, column)

    protected open fun assembleTiles(rc: RenderContext) {
        // Clear previous terrain tiles
        currentTerrain.clear()

        // Assemble the terrain buffers and OpenGL buffer objects associated with the level set.
        assembleLevelSetBuffers(rc)
        currentTerrain.triStripElements = levelSetTriStripElements

        // Assemble the tessellator's top level terrain tiles, which we keep permanent references to.
        if (topLevelTiles.isEmpty()) createTopLevelTiles()

        // Subdivide the top level tiles until the desired resolution is achieved in each part of the scene.
        for (tile in topLevelTiles) addTileOrDescendants(rc, tile as TerrainTile)

        // Sort terrain tiles by L1 distance on cylinder from camera
        currentTerrain.sort()

        // Release references to render resources acquired while assembling tiles.
        levelSetVertexTexCoordBuffer = null
        levelSetElementBuffer = null
    }

    protected open fun createTopLevelTiles() {
        levelSet.firstLevel?.let{ Tile.assembleTilesForLevel(it, this, topLevelTiles) }
    }

    protected open fun addTileOrDescendants(rc: RenderContext, tile: TerrainTile) {
        // ignore the tile and its descendants if it's not needed or not visible
        if (!tile.intersectsSector(levelSet.sector) || !tile.intersectsFrustum(rc)) return
        if (tile.level.isLastLevel || !tile.mustSubdivide(rc, detailControl)) {
            addTile(rc, tile)
            return  // use the tile if it does not need to be subdivided
        }
        for (child in tile.subdivideToCache(this, tileCache, 4)) // each tile has a cached size of 1
            addTileOrDescendants(rc, child as TerrainTile) // recursively process the tile's children
    }

    protected open fun addTile(rc: RenderContext, tile: TerrainTile) {
        // Prepare the terrain tile and add it.
        tile.prepare(rc)
        currentTerrain.addTile(tile)

        // Prepare a drawable for the terrain tile for processing on the OpenGL thread.
        val pool = rc.getDrawablePool<BasicDrawableTerrain>()
        val drawable = BasicDrawableTerrain.obtain(pool)
        prepareDrawableTerrain(rc, tile, drawable)
        rc.offerDrawableTerrain(drawable, tile.sortOrder)
    }

    protected open fun invalidateTiles() {
        topLevelTiles.clear()
        currentTerrain.clear()
        lastTerrain.clear()
        tileCache.clear()
        levelSetVertexTexCoords = null
        levelSetLineElements = null
        levelSetTriStripElements = null
    }

    protected open fun prepareDrawableTerrain(rc: RenderContext, tile: TerrainTile, drawable: BasicDrawableTerrain) {
        // Assemble the drawable's geographic sector and Cartesian vertex origin.
        drawable.sector.copy(tile.sector)
        drawable.vertexOrigin.copy(tile.origin)

        // Assemble the drawable's element buffer ranges.
        drawable.lineElementRange.copy(levelSetLineElementRange)
        drawable.triStripElementRange.copy(levelSetTriStripElementRange)

        // Assemble the drawable's OpenGL buffer objects.
        drawable.vertexPoints = tile.getPointBuffer(rc)
        drawable.vertexTexCoords = levelSetVertexTexCoordBuffer
        drawable.elements = levelSetElementBuffer
    }

    protected open fun assembleLevelSetBuffers(rc: RenderContext) {
        val numLat = levelSet.tileHeight + 2
        val numLon = levelSet.tileWidth + 2

        // Assemble the level set's vertex tex coords.
        val vertexTexCoords = levelSetVertexTexCoords ?: FloatArray(numLat * numLon * 2).also {
            levelSetVertexTexCoords = it
            assembleVertexTexCoords(numLat, numLon, it)
        }

        // Assemble the level set's line elements.
        val lineElements = levelSetLineElements ?: assembleLineElements(numLat, numLon).also { levelSetLineElements = it }

        // Assemble the level set's triangle strip elements.
        val triStripElements = levelSetTriStripElements ?: assembleTriStripElements(numLat, numLon).also { levelSetTriStripElements = it }

        // Retrieve or create the level set's OpenGL vertex tex coord buffer object.
        levelSetVertexTexCoordBuffer = rc.getBufferObject(levelSetVertexTexCoordKey) {
            FloatBufferObject(GL_ARRAY_BUFFER, vertexTexCoords)
        }

        // Retrieve or create the level set's OpenGL element buffer object.
        levelSetElementBuffer = rc.getBufferObject(levelSetElementKey) {
            ShortBufferObject(GL_ELEMENT_ARRAY_BUFFER, lineElements + triStripElements).also {
                levelSetLineElementRange.upper = lineElements.size
                levelSetTriStripElementRange.lower = lineElements.size
                levelSetTriStripElementRange.upper = lineElements.size + triStripElements.size
            }
        }
    }

    protected open fun assembleVertexTexCoords(numLat: Int, numLon: Int, result: FloatArray): FloatArray {
        val ds = 1f / if (numLon > 1) numLon - 3 else 1
        val dt = 1f / if (numLat > 1) numLat - 3 else 1
        var s = 0f
        var t = 0f
        var rIdx = 0

        // Iterate over the number of latitude and longitude vertices, computing the parameterized S and T coordinates
        // corresponding to each vertex.
        for (tIdx in 0 until numLat) {
            when {
                tIdx < 2 -> t = 0f // explicitly set the first T coordinate to 0 to ensure alignment
                tIdx < numLat - 2 -> t += dt
                else -> t = 1f // explicitly set the last T coordinate to 1 to ensure alignment
            }
            for (sIdx in 0 until numLon) {
                when {
                    sIdx < 2 -> s = 0f // explicitly set the first S coordinate to 0 to ensure alignment
                    sIdx < numLon - 2 -> s += ds
                    else -> s = 1f // explicitly set the last S coordinate to 1 to ensure alignment
                }
                result[rIdx++] = s
                result[rIdx++] = t
            }
        }
        return result
    }

    protected open fun assembleLineElements(numLat: Int, numLon: Int): ShortArray {
        // Allocate a buffer to hold the indices.
        val count = (numLat * (numLon - 1) + numLon * (numLat - 1)) * 2
        val result = ShortArray(count)
        var pos = 0

        // Add a line between each row to define the horizontal cell outlines.
        for (latIndex in 0 until numLat) {
            for (lonIndex in 0 until numLon - 1) {
                val vertex = lonIndex + latIndex * numLon
                result[pos++] = vertex.toShort()
                result[pos++] = (vertex + 1).toShort()
            }
        }

        // Add a line between each column to define the vertical cell outlines.
        for (lonIndex in 0 until numLon) {
            for (latIndex in 0 until numLat - 1) {
                val vertex = lonIndex + latIndex * numLon
                result[pos++] = vertex.toShort()
                result[pos++] = (vertex + numLon).toShort()
            }
        }
        return result
    }

    protected open fun assembleTriStripElements(numLat: Int, numLon: Int): ShortArray {
        // Allocate a buffer to hold the indices.
        val count = ((numLat - 1) * numLon + (numLat - 2)) * 2
        val result = ShortArray(count)
        var pos = 0
        var vertex = 0
        for (latIndex in 0 until numLat - 1) {
            // Create a triangle strip joining each adjacent column of vertices, starting in the bottom left corner and
            // proceeding to the right. The first vertex starts with the left row of vertices and moves right to create
            // a counterclockwise winding order.
            for (lonIndex in 0 until numLon) {
                vertex = lonIndex + latIndex * numLon
                result[pos++] = (vertex + numLon).toShort()
                result[pos++] = vertex.toShort()
            }

            // Insert indices to create 2 degenerate triangles:
            // - one for the end of the current row, and
            // - one for the beginning of the next row
            if (latIndex < numLat - 2) {
                result[pos++] = vertex.toShort()
                result[pos++] = ((latIndex + 2) * numLon).toShort()
            }
        }
        return result
    }
}