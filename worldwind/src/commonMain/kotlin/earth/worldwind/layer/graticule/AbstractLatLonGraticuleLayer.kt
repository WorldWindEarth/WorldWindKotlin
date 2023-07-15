package earth.worldwind.layer.graticule

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.render.RenderContext
import earth.worldwind.util.format.format
import kotlin.math.floor

abstract class AbstractLatLonGraticuleLayer(name: String): AbstractGraticuleLayer(name), GridTilesSupport.Callback {
    enum class AngleFormat { DD, DM, DMS; }

    private val gridTilesSupport = GridTilesSupport(this, 18, 36)
    private val latitudeLabels = mutableListOf<Angle>()
    private val longitudeLabels= mutableListOf<Angle>()

    /**
     * The graticule division and angular display format. Can be one of [AngleFormat.DD],
     * [AngleFormat.DMS] or [AngleFormat.DM].
     */
    var angleFormat = AngleFormat.DMS
        set(format) {
            if (field == format) return
            field = format
            gridTilesSupport.clearTiles()
        }

    override fun clear(rc: RenderContext) {
        super.clear(rc)
        latitudeLabels.clear()
        longitudeLabels.clear()
    }

    override fun selectRenderables(rc: RenderContext) {
        gridTilesSupport.selectRenderables(rc)
    }

    override fun getGridSector(row: Int, col: Int): Sector {
        val minLat = -90.0 + row * 10
        val maxLat = minLat + 10
        val minLon = -180.0 + col * 10
        val maxLon = minLon + 10
        return fromDegrees(minLat, minLon, maxLat - minLat, maxLon - minLon)
    }

    override fun getGridColumn(longitude: Angle) = floor((longitude.inDegrees + 180) / 10.0).toInt().coerceAtMost(35)

    override fun getGridRow(latitude: Angle) = floor((latitude.inDegrees+ 90) / 10.0).toInt().coerceAtMost(17)

    fun addLabel(value: Angle, labelType: String, graticuleType: String, resolution: Double, labelOffset: Location) {
        var position: Position? = null
        if (labelType == GridElement.TYPE_LATITUDE_LABEL) {
            if (!latitudeLabels.contains(value)) {
                latitudeLabels.add(value)
                position = Position(value, labelOffset.longitude, 0.0)
            }
        } else if (labelType == GridElement.TYPE_LONGITUDE_LABEL) {
            if (!longitudeLabels.contains(value)) {
                longitudeLabels.add(value)
                position = Position(labelOffset.latitude, value, 0.0)
            }
        }
        if (position != null) {
            val label = makeAngleLabel(value, resolution)
            addRenderable(createTextRenderable(position, label, resolution), graticuleType)
        }
    }

    private fun makeAngleLabel(angle: Angle, resolution: Double): String {
        val epsilon = .000000001
        val label = if (angleFormat == AngleFormat.DMS) {
            if (resolution >= 1) angle.toDecimalDegreesString(0)
            else {
                val dms = angle.toDMS()
                if (dms[2] < epsilon && dms[3] < epsilon) "${if (dms[0] < 0) "-" else ""}%d°".format(dms[1].toInt())
                else if (dms[3] < epsilon) "${if (dms[0] < 0) "-" else ""}%d° %2d’".format(dms[1].toInt(), dms[2].toInt())
                else angle.toDMSString()
            }
        } else if (angleFormat == AngleFormat.DM) {
            if (resolution >= 1) angle.toDecimalDegreesString(0)
            else {
                val dms = angle.toDMS()
                if (dms[2] < epsilon && dms[3] < epsilon) "${if (dms[0] < 0) "-" else ""}%d°".format(dms[1].toInt())
                else if (dms[3] < epsilon) "${if (dms[0] < 0) "-" else ""}%d° %2d’".format(dms[1].toInt(), dms[2].toInt())
                else angle.toDMString()
            }
        } else { // default to decimal degrees
            when {
                resolution >= 1 -> angle.toDecimalDegreesString(0)
                resolution >= .1 -> angle.toDecimalDegreesString(1)
                resolution >= .01 -> angle.toDecimalDegreesString(2)
                resolution >= .001 -> angle.toDecimalDegreesString(3)
                else -> angle.toDecimalDegreesString(4)
            }
        }
        return label
    }
}