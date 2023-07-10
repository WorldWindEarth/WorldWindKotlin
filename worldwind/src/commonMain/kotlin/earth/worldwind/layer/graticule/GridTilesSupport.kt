package earth.worldwind.layer.graticule

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Viewport
import earth.worldwind.render.RenderContext

internal class GridTilesSupport(private val callback: Callback, private val rows: Int, private val cols: Int) {
    internal interface Callback {
        fun createGridTile(sector: Sector): AbstractGraticuleTile
        fun getGridSector(row: Int, col: Int): Sector
        fun getGridColumn(longitude: Angle): Int
        fun getGridRow(latitude: Angle): Int
    }

    private val gridTiles = Array(rows) { arrayOfNulls<AbstractGraticuleTile>(cols) }

    fun clearTiles() {
        for (row in 0 until rows) for (col in 0 until cols) {
            gridTiles[row][col]?.clearRenderables()
            gridTiles[row][col] = null
        }
    }

    /**
     * Select the visible grid elements
     *
     * @param rc the current `RenderContext`.
     */
    fun selectRenderables(rc: RenderContext) {
        val tileList = getVisibleTiles(rc)
        // Select tile visible elements
        for (gt in tileList) gt.selectRenderables(rc)
    }

    private fun getVisibleTiles(rc: RenderContext): List<AbstractGraticuleTile> {
        val tileList = mutableListOf<AbstractGraticuleTile>()
        val vs = rc.terrain!!.sector
        val gridRectangle = getGridRectangleForSector(vs)
        for (row in gridRectangle.y..gridRectangle.height) {
            for (col in gridRectangle.x..gridRectangle.width) {
                val tile = gridTiles[row][col] ?: callback.createGridTile(callback.getGridSector(row, col)).also {
                    gridTiles[row][col] = it
                }
                if (tile.isInView(rc)) tileList.add(tile) else tile.clearRenderables()
            }
        }
        return tileList
    }

    private fun getGridRectangleForSector(sector: Sector): Viewport {
        val x1 = callback.getGridColumn(sector.minLongitude)
        val x2 = callback.getGridColumn(sector.maxLongitude)
        val y1 = callback.getGridRow(sector.minLatitude)
        val y2 = callback.getGridRow(sector.maxLatitude)
        return Viewport(x1, y1, x2, y2) // Viewport is used as simple integer rectangle
    }
}