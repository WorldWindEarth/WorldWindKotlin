package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.DownloadPostprocessor
import earth.worldwind.util.Level
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.TileFactory

open class WmtsTileFactory(var template: String, var tileMatrixIdentifiers: List<String>): TileFactory {
    companion object {
        const val TILEMATRIX_TEMPLATE = "{TileMatrix}"
        const val TILEROW_TEMPLATE = "{TileRow}"
        const val TILECOL_TEMPLATE = "{TileCol}"
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) = ImageTile(sector, level, row, column).apply {
        urlForTile(level.levelNumber, row, column)?.let { urlString ->
            imageSource = ImageSource.fromUrlString(urlString, this as DownloadPostprocessor<*>)
        }
    }

    fun urlForTile(level: Int, row: Int, column: Int): String? {
        if (level >= tileMatrixIdentifiers.size) {
            logMessage(
                Logger.WARN, "WmtsTileFactory", "urlForTile",
                "invalid level for tileMatrixIdentifiers: $level"
            )
            return null
        }

        // flip the row index
        val rowHeight = 2 shl level
        val flipRow = rowHeight - row - 1
        return template.replace(TILEMATRIX_TEMPLATE, tileMatrixIdentifiers[level])
            .replace(TILEROW_TEMPLATE, flipRow.toString()).replace(TILECOL_TEMPLATE, column.toString())
    }
}