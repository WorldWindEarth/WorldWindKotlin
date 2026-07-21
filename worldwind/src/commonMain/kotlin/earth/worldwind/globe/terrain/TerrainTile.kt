package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Level
import earth.worldwind.util.NumericArray
import earth.worldwind.util.Tile
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import kotlin.math.sqrt

/**
 * Represents a portion of a globe's terrain. Applications typically do not interact directly with this class.
 */
open class TerrainTile(sector: Sector, level: Level, row: Int, column: Int): Tile(sector, level, row, column) {
    val origin = Vec3()
    val points by lazy { FloatArray((level.tileWidth + 2) * (level.tileHeight + 2) * 3) }
    protected val normals by lazy { FloatArray((level.tileWidth + 2) * (level.tileHeight + 2) * 3) }
    protected val heights by lazy { FloatArray( (level.tileWidth + 2) * (level.tileHeight + 2)) }
    protected val heightGrid by lazy { FloatArray( level.tileWidth * level.tileHeight) }
    /**
     * Minimum elevation value used by the BasicTessellator to determine the terrain mesh edge extension depth (skirt).
     * This value is scaled by the vertical exaggeration when the terrain is generated.
     */
    protected val minTerrainElevation = -Short.MAX_VALUE.toFloat()
    protected var heightTimestamp = 0L
    protected var globeVE = 0.0
    protected var globeState: Globe.State? = null
    protected var globeOffset: Globe.Offset? = null
    var sortOrder = 0.0
        protected set
    // Stable per-tile keys so the cached BufferObject is reused; re-uploads go via version bump.
    private val pointBufferKey = "TerrainTile.points.$tileKey"
    private val heightBufferKey = "TerrainTile.heights.$tileKey"
    private val normalBufferKey = "TerrainTile.normals.$tileKey"
    // Public read access lets terrain point caches detect when this tile's geometry changed.
    var pointBufferVersion = 0
        protected set
    private var heightBufferVersion = 0
    private var normalBufferVersion = 0
    // Recomputed lazily on the next getNormalBuffer after the point grid changes.
    private var normalsDirty = true

    public override val heightLimits get() = super.heightLimits

    open fun prepare(rc: RenderContext) {
        val globe = rc.globe
        val tileWidth = level.tileWidth
        val tileHeight = level.tileHeight
        val timestamp = rc.elevationModelTimestamp
        if (timestamp != heightTimestamp) {
            heightGrid.fill(0f)
            globe.getElevationGrid(sector, tileWidth, tileHeight, heightGrid)
            // Calculate height vertex buffer from height grid
            for (r in 0 until level.tileHeight) for (c in 0 until level.tileWidth) {
                heights[(r + 1) * (level.tileWidth + 2) + c + 1] = heightGrid[r * level.tileWidth + c]
            }
            if (rc.globe.is2D) {
                heightGrid.fill(0f) // Do not show terrain in 2D, but keep height values in vertex for heatmap
                calcHeightLimits(globe) // Force calculate height limits for heatmap
            }
            updateHeightBufferKey()
        }
        val ve = rc.globe.verticalExaggeration
        val state = rc.globeState
        val offset = rc.globe.offset
        if (timestamp != heightTimestamp || ve != globeVE || state != globeState || offset != globeOffset) {
            val rowStride = (tileWidth + 2) * 3
            globe.geographicToCartesian(sector.centroidLatitude, sector.centroidLongitude, 0.0, origin)
            globe.geographicToCartesianGrid(
                sector, tileWidth, tileHeight, heightGrid, origin, points, rowStride + 3, rowStride
            )
            globe.geographicToCartesianBorder(
                sector, tileWidth + 2, tileHeight + 2, minTerrainElevation, origin, points
            )
            updatePointBufferKey()
            normalsDirty = true
        }
        heightTimestamp = timestamp
        globeVE = ve
        globeState = state
        globeOffset = offset
        sortOrder = drawSortOrder(rc)
    }

    fun getHeightBuffer(rc: RenderContext) : BufferObject {
        val buffer = rc.getBufferObject(heightBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(heightBufferKey, heightBufferVersion) { NumericArray.Floats(heights) }
        return buffer
    }

    fun getPointBuffer(rc: RenderContext) : BufferObject {
        val buffer = rc.getBufferObject(pointBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(pointBufferKey, pointBufferVersion) { NumericArray.Floats(points) }
        return buffer
    }

    /**
     * Smooth per-vertex terrain normals for relief shading, derived from the Cartesian point
     * grid by clamped central differences. The skirt ring copies its interior edge normal so
     * skirt walls shade like the rim. Computed lazily on first request after a point update.
     */
    fun getNormalBuffer(rc: RenderContext) : BufferObject {
        if (normalsDirty) {
            computeNormals(rc.globe)
            normalsDirty = false
            updateNormalBufferKey()
        }
        val buffer = rc.getBufferObject(normalBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(normalBufferKey, normalBufferVersion) { NumericArray.Floats(normals) }
        return buffer
    }

    protected open fun computeNormals(globe: Globe) {
        val w = level.tileWidth
        val h = level.tileHeight
        val cols = w + 2
        val rows = h + 2
        // One-cell-expanded grid: central differences everywhere, and same-level neighbours sample identical edge points (no seams).
        val dLat = sector.deltaLatitude.inDegrees / (h - 1)
        val dLon = sector.deltaLongitude.inDegrees / (w - 1)
        val minLon = sector.minLongitude.inDegrees - dLon
        val maxLon = sector.maxLongitude.inDegrees + dLon
        // Raw constructor - Sector.set clamps max lat/lon, which would skew the whole grid's spacing.
        val expandedSector = Sector(
            fromDegrees(sector.minLatitude.inDegrees - dLat), fromDegrees(sector.maxLatitude.inDegrees + dLat),
            fromDegrees(minLon), fromDegrees(maxLon),
        )
        // Transient scratch - normals recompute only on elevation / globe-state changes.
        val expHeights = FloatArray(rows * cols)
        val expPoints = FloatArray(rows * cols * 3)
        // 2D globes render a flat mesh (prepare zeroes the height grid) - keep normals flat too.
        if (!globe.is2D) {
            globe.getElevationGrid(expandedSector, cols, rows, expHeights)
            // Ring columns past the antimeridian read no coverage - re-fetch them wrapped.
            if (minLon < -180.0) patchWrappedColumn(globe, expandedSector, expHeights, minLon + 360.0, 0)
            if (maxLon > 180.0) patchWrappedColumn(globe, expandedSector, expHeights, maxLon - 360.0, cols - 1)
            // A ring row past a pole keeps height 0 - only the degenerate pole-edge vertices see it.
        }
        globe.geographicToCartesianGrid(expandedSector, rows, cols, expHeights, origin, expPoints)
        // Interior vertices occupy rows 1..h, cols 1..w; the surrounding ring is the skirt.
        for (r in 1..h) for (c in 1..w) {
            val vi = r * cols + c
            val e = (r * cols + c + 1) * 3
            val ww = (r * cols + c - 1) * 3
            val n = ((r + 1) * cols + c) * 3
            val s = ((r - 1) * cols + c) * 3
            val ax = expPoints[e] - expPoints[ww]
            val ay = expPoints[e + 1] - expPoints[ww + 1]
            val az = expPoints[e + 2] - expPoints[ww + 2]
            val bx = expPoints[n] - expPoints[s]
            val by = expPoints[n + 1] - expPoints[s + 1]
            val bz = expPoints[n + 2] - expPoints[s + 2]
            var nx = (ay * bz - az * by).toDouble()
            var ny = (az * bx - ax * bz).toDouble()
            var nz = (ax * by - ay * bx).toDouble()
            // Orient outward: the absolute vertex position (origin + point) is the up reference.
            val px = origin.x + expPoints[vi * 3]
            val py = origin.y + expPoints[vi * 3 + 1]
            val pz = origin.z + expPoints[vi * 3 + 2]
            if (nx * px + ny * py + nz * pz < 0.0) { nx = -nx; ny = -ny; nz = -nz }
            var len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0.0) {
                normals[vi * 3] = (nx / len).toFloat()
                normals[vi * 3 + 1] = (ny / len).toFloat()
                normals[vi * 3 + 2] = (nz / len).toFloat()
            } else {
                // Degenerate cell (pole pinch) - fall back to the radial up.
                len = sqrt(px * px + py * py + pz * pz)
                normals[vi * 3] = if (len > 0.0) (px / len).toFloat() else 0f
                normals[vi * 3 + 1] = if (len > 0.0) (py / len).toFloat() else 0f
                normals[vi * 3 + 2] = if (len > 0.0) (pz / len).toFloat() else 1f
            }
        }
        // Skirt ring copies the nearest interior normal.
        for (c in 0 until cols) {
            val ci = c.coerceIn(1, w)
            copyNormal(c, cols + ci)
            copyNormal((h + 1) * cols + c, h * cols + ci)
        }
        for (r in 0 until h + 2) {
            val ri = r.coerceIn(1, h)
            copyNormal(r * cols, ri * cols + 1)
            copyNormal(r * cols + w + 1, ri * cols + w)
        }
    }

    private fun copyNormal(dst: Int, src: Int) {
        normals[dst * 3] = normals[src * 3]
        normals[dst * 3 + 1] = normals[src * 3 + 1]
        normals[dst * 3 + 2] = normals[src * 3 + 2]
    }

    /** Re-fetches one expanded-grid ring column at its antimeridian-wrapped longitude. */
    private fun patchWrappedColumn(
        globe: Globe, expandedSector: Sector, expHeights: FloatArray, lonDegrees: Double, col: Int
    ) {
        val cols = level.tileWidth + 2
        val rows = level.tileHeight + 2
        val dLon = sector.deltaLongitude.inDegrees / (level.tileWidth - 1)
        // Two-column strip inside [-180, 180] whose west or east column lands on the wrapped longitude.
        val stripMin = if (lonDegrees + dLon <= 180.0) lonDegrees else lonDegrees - dLon
        val stripCol = if (stripMin == lonDegrees) 0 else 1
        val strip = Sector(
            expandedSector.minLatitude, expandedSector.maxLatitude,
            fromDegrees(stripMin), fromDegrees(stripMin + dLon),
        )
        val stripHeights = FloatArray(rows * 2)
        globe.getElevationGrid(strip, 2, rows, stripHeights)
        for (r in 0 until rows) expHeights[r * cols + col] = stripHeights[r * 2 + stripCol]
    }

    protected open fun updateHeightBufferKey() {
        heightBufferVersion = (++bufferSequence).toInt()
    }

    protected open fun updatePointBufferKey() {
        pointBufferVersion = (++bufferSequence).toInt()
    }

    protected open fun updateNormalBufferKey() {
        normalBufferVersion = (++bufferSequence).toInt()
    }

    companion object {
        // Global monotonic counter — a re-created tile's first upload still beats its predecessor's cached version.
        private var bufferSequence = 0L
    }
}