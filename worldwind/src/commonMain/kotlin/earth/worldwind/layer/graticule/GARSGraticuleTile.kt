package earth.worldwind.layer.graticule

import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_GRIDZONE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LATITUDE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_NORTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_SOUTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_WEST
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LONGITUDE_LABEL
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType
import earth.worldwind.util.format.format

internal class GARSGraticuleTile(
    layer: GARSGraticuleLayer, sector: Sector, private val divisions: Int, private val level: Int
): AbstractGraticuleTile(layer, sector) {
    companion object {
        /**
         * Indicates the eye altitudes in meters below which each level should be displayed.
         */
        private val THRESHOLDS = doubleArrayOf(600e3, 300e3, 90e3) // 30 min, 15 min, 5 min
        /**
         * The eye altitude below which the 30 minute grid is displayed.
         */
        var threshold30Min: Double
            get() = THRESHOLDS[0]
            set(value) { THRESHOLDS[0] = value }
        /**
         * The eye altitude below which the 15 minute grid is displayed.
         */
        var threshold15Min: Double
            get() = THRESHOLDS[1]
            set(value) { THRESHOLDS[1] = value }
        /**
         * The eye altitude below which the 5 minute grid is displayed.
         */
        var threshold5Min: Double
            get() = THRESHOLDS[2]
            set(value) { THRESHOLDS[2] = value }

        private val LAT_LABELS = Array(360) {
            val length = CHARS.length
            val i1 = it / length
            val i2 = it % length
            "%c%c".format(CHARS[i1], CHARS[i2])
        }
        private val LON_LABELS = Array(720) { "%03d".format(it + 1) }
        private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private val LEVEL_2_LABELS = arrayOf(arrayOf("3", "4"), arrayOf("1", "2"))

        private fun makeLabelLevel1(sector: Sector): String {
            val iLat = ((90 + sector.centroidLatitude.degrees) * 60 / 30).toInt()
            val iLon = ((180 + sector.centroidLongitude.degrees) * 60 / 30).toInt()
            return LON_LABELS[iLon] + LAT_LABELS[iLat]
        }

        private fun makeLabelLevel2(sector: Sector): String {
            val minutesLat = ((90 + sector.minLatitude.degrees) * 60).toInt()
            val j = minutesLat % 30 / 15
            val minutesLon = ((180 + sector.minLongitude.degrees) * 60).toInt()
            val i = minutesLon % 30 / 15
            return LEVEL_2_LABELS[j][i]
        }
    }

    private var subTiles: MutableList<GARSGraticuleTile>? = null
    override val layer get() = super.layer as GARSGraticuleLayer

    override fun isInView(rc: RenderContext) =
        super.isInView(rc) && (level == 0 || rc.camera!!.position.altitude <= THRESHOLDS[level - 1])

    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        var graticuleType = layer.getTypeFor(sector.deltaLatitude.degrees)
        if (level == 0 && rc.camera!!.position.altitude > THRESHOLDS[0]) {
            val labelOffset = layer.computeLabelOffset(rc)
            for (ge in gridElements!!) {
                if (ge.isInView(rc)) {
                    // Add level zero bounding lines and labels
                    if (ge.type == TYPE_LINE_SOUTH || ge.type == TYPE_LINE_NORTH || ge.type == TYPE_LINE_WEST) {
                        layer.addRenderable(ge.renderable, graticuleType)
                        val labelType = if (ge.type == TYPE_LINE_SOUTH || ge.type == TYPE_LINE_NORTH)
                            TYPE_LATITUDE_LABEL else TYPE_LONGITUDE_LABEL
                        layer.addLabel(
                            ge.value, labelType, graticuleType, sector.deltaLatitude.degrees, labelOffset
                        )
                    }
                }
            }
            if (rc.camera!!.position.altitude > THRESHOLDS[0]) return
        }

        // Select tile grid elements
        val eyeDistance = rc.camera!!.position.altitude
        if (level == 0 && eyeDistance <= THRESHOLDS[0] || level == 1 && eyeDistance <= THRESHOLDS[1] || level == 2) {
            val resolution = sector.deltaLatitude.degrees / divisions
            graticuleType = layer.getTypeFor(resolution)
            for (ge in gridElements!!) if (ge.isInView(rc)) layer.addRenderable(ge.renderable, graticuleType)
        }
        if (level == 0 && eyeDistance > THRESHOLDS[1]) return
        else if (level == 1 && eyeDistance > THRESHOLDS[2]) return
        else if (level == 2) return

        // Select child elements
        subTiles ?: createSubTiles()
        for (gt in subTiles!!) if (gt.isInView(rc)) gt.selectRenderables(rc) else gt.clearRenderables()
    }

    override fun clearRenderables() {
        super.clearRenderables()
        subTiles?.run {
            for (gt in this) gt.clearRenderables()
            clear()
        }.also { subTiles = null }
    }

    private fun createSubTiles() {
        subTiles = mutableListOf()
        val sectors = subdivide(divisions)
        val nextLevel = level + 1
        var subDivisions = 10
        if (nextLevel == 1) subDivisions = 2 else if (nextLevel == 2) subDivisions = 3
        for (s in sectors) subTiles!!.add(GARSGraticuleTile(layer, s, subDivisions, nextLevel))
    }

    override fun createRenderables() {
        super.createRenderables()
        val step = sector.deltaLatitude.degrees / divisions

        // Generate meridians with labels
        var lon = sector.minLongitude.plusDegrees(if (level == 0) 0.0 else step)
        while (lon.degrees < sector.maxLongitude.degrees - step / 2) {
            // Meridian
            val positions = listOf(
                Position(sector.minLatitude, lon, 0.0), Position(sector.maxLatitude, lon, 0.0)
            )
            val line = layer.createLineRenderable(positions, PathType.LINEAR)
            val sector = Sector(sector.minLatitude, sector.maxLatitude, lon, lon)
            val lineType = if (lon == sector.minLongitude) TYPE_LINE_WEST else TYPE_LINE
            gridElements!!.add(GridElement(sector, line, lineType, lon))

            // Increase longitude
            lon = lon.plusDegrees(step)
        }

        // Generate parallels
        var lat = sector.minLatitude.plusDegrees(if (level == 0) 0.0 else step)
        while (lat.degrees < sector.maxLatitude.degrees - step / 2) {
            val positions = listOf(
                Position(lat, sector.minLongitude, 0.0), Position(lat, sector.maxLongitude, 0.0)
            )
            val line = layer.createLineRenderable(positions, PathType.LINEAR)
            val sector = Sector(lat, lat, sector.minLongitude, sector.maxLongitude)
            val lineType = if (lat == sector.minLatitude) TYPE_LINE_SOUTH else TYPE_LINE
            gridElements!!.add(GridElement(sector, line, lineType, lat))

            // Increase latitude
            lat = lat.plusDegrees(step)
        }

        // Draw and label a parallel at the top of the graticule. The line is apparent only on 2D globes.
        if (sector.maxLatitude == POS90) {
            val positions = listOf(
                Position(POS90, sector.minLongitude, 0.0), Position(POS90, sector.maxLongitude, 0.0)
            )
            val line = layer.createLineRenderable(positions, PathType.LINEAR)
            val sector = Sector(POS90, POS90, sector.minLongitude, sector.maxLongitude)
            gridElements!!.add(GridElement(sector, line, TYPE_LINE_NORTH, POS90))
        }
        var resolution = sector.deltaLatitude.degrees / divisions
        when (level) {
            0 -> {
                val sectors = subdivide(20)
                for (j in 0..19) {
                    for (i in 0..19) {
                        val sector = sectors[j * 20 + i]
                        val label = makeLabelLevel1(sector)
                        addLabel(label, sectors[j * 20 + i], resolution)
                    }
                }
            }
            1 -> {
                val label = makeLabelLevel1(sector)
                val sectors = subdivide(2)
                addLabel(label + "3", sectors[0], resolution)
                addLabel(label + "4", sectors[1], resolution)
                addLabel(label + "1", sectors[2], resolution)
                addLabel(label + "2", sectors[3], resolution)
            }
            2 -> {
                var label = makeLabelLevel1(sector)
                label += makeLabelLevel2(sector)
                resolution = 0.26 // make label priority a little higher than level 2's
                val sectors = subdivide(3)
                addLabel(label + "7", sectors[0], resolution)
                addLabel(label + "8", sectors[1], resolution)
                addLabel(label + "9", sectors[2], resolution)
                addLabel(label + "4", sectors[3], resolution)
                addLabel(label + "5", sectors[4], resolution)
                addLabel(label + "6", sectors[5], resolution)
                addLabel(label + "1", sectors[6], resolution)
                addLabel(label + "2", sectors[7], resolution)
                addLabel(label + "3", sectors[8], resolution)
            }
        }
    }

    private fun addLabel(label: String, sector: Sector, resolution: Double) {
        val text = layer.createTextRenderable(
            Position(sector.centroidLatitude, sector.centroidLongitude, 0.0), label, resolution
        )
        gridElements!!.add(GridElement(sector, text, TYPE_GRIDZONE_LABEL))
    }
}