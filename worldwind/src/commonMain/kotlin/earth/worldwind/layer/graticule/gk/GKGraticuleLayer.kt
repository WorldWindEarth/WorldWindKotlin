package earth.worldwind.layer.graticule.gk

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.HelmertParameters
import earth.worldwind.geom.coords.HelmertTransformation
import earth.worldwind.layer.graticule.AbstractGraticuleLayer
import earth.worldwind.layer.graticule.GraticuleRenderingParams
import earth.worldwind.layer.graticule.GridTilesSupport
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Label
import kotlin.math.floor

class GKGraticuleLayer(
    private val toWgsParameters: HelmertParameters = HelmertParameters.SK42_WGS84,
    private val fromWgsParameters: HelmertParameters = HelmertParameters.WGS84_SK42
) : AbstractGraticuleLayer("Gauss-Kruger Graticule"), GridTilesSupport.Callback {
    /**
     * Show 25k and 10k sheets when metric graticule becomes visible
     */
    var showDetailedSheets = false
    /**
     * Maximal visibility distance for 1km grid labels
     */
    var thresholdFor1kLabels = GK_MAX_RESOLUTION_25_000 * 2.0
    /**
     * Maximal visibility distance for 2km grid labels
     */
    var thresholdFor2kLabels = GK_MAX_RESOLUTION_50_000 * 2.0

    private val gridTilesSupport = GridTilesSupport(this, 46, 60)
    private val overview = GKOverview(this)
    private val metricLabels = GKMetricLabels(this )
    private var metricLabelScale = 0

    override val orderedTypes = listOf(
        GRATICULE_GK_OVERVIEW,
        GRATICULE_GK_1_000_000,
        GRATICULE_GK_500_000,
        GRATICULE_GK_200_000,
        GRATICULE_GK_100_000,
        GRATICULE_GK_50_000,
        GRATICULE_GK_25_000,
        GRATICULE_GK_10_000
    )

    override fun initRenderingParams() {
        var params = GraticuleRenderingParams()
        params[GraticuleRenderingParams.KEY_LINE_COLOR] = Color(0, 0, 0)
        params[GraticuleRenderingParams.KEY_LABEL_COLOR] = Color(0, 0, 0)
        params[GraticuleRenderingParams.KEY_LABEL_FONT] = Font("arial", FontWeight.NORMAL, 11)
        setRenderingParams(GK_METRIC_GRID_2000, params)
        setRenderingParams(GK_METRIC_GRID_1000, params)

        params = GraticuleRenderingParams()
        params[GraticuleRenderingParams.KEY_LINE_COLOR] = Color(255, 0, 0)
        params[GraticuleRenderingParams.KEY_LABEL_COLOR] = Color(255, 0, 0)
        params[GraticuleRenderingParams.KEY_LABEL_FONT] = Font("arial", FontWeight.NORMAL, 13)
        setRenderingParams(GRATICULE_GK_OVERVIEW, params)
        setRenderingParams(GRATICULE_GK_1_000_000, params)
        setRenderingParams(GRATICULE_GK_500_000, params)
        setRenderingParams(GRATICULE_GK_200_000, params)
        setRenderingParams(GRATICULE_GK_100_000, params)
        setRenderingParams(GRATICULE_GK_50_000, params)
        setRenderingParams(GRATICULE_GK_25_000, params)
        setRenderingParams(GRATICULE_GK_10_000, params)
    }

    override fun selectRenderables(rc: RenderContext) {
        metricLabelScale = 0
        if (rc.camera.position.altitude < GK_MAX_RESOLUTION_OVERVIEW) {
            gridTilesSupport.selectRenderables(rc)
            metricLabels.selectRenderables(rc, metricLabelScale)
        } else {
            overview.selectRenderables(rc)
        }
    }

    override fun getGridSector(row: Int, col: Int): Sector {
        var minLat = -92.0 + row * 4
        var maxLat = minLat + 4
        if (row == 0) {
            minLat = -90.0
            maxLat = -88.0
        } else if (row == 45) {
            maxLat = 90.0
            minLat = 88.0
        }
        val minLon = -180.0 + col * 6
        val maxLon = minLon + 6
        return Sector.fromDegrees(minLat, minLon, maxLat - minLat, maxLon - minLon)
    }

    override fun getGridColumn(longitude: Angle) = floor((longitude.inDegrees + 180) / 6.0).toInt().coerceAtMost(59)

    override fun getProjectedSector(sector: Sector) = Sector().apply {
        union(transformFromWGS(Position(sector.minLatitude, sector.minLongitude, 0.0)))
        union(transformFromWGS(Position(sector.minLatitude, sector.maxLongitude, 0.0)))
        union(transformFromWGS(Position(sector.maxLatitude, sector.minLongitude, 0.0)))
        union(transformFromWGS(Position(sector.maxLatitude, sector.maxLongitude, 0.0)))
    }

    fun getUnprojectedSector(sector: Sector) = Sector().apply {
        union(transformToWGS(Position(sector.minLatitude, sector.minLongitude, 0.0)))
        union(transformToWGS(Position(sector.minLatitude, sector.maxLongitude, 0.0)))
        union(transformToWGS(Position(sector.maxLatitude, sector.minLongitude, 0.0)))
        union(transformToWGS(Position(sector.maxLatitude, sector.maxLongitude, 0.0)))
    }

    fun transformFromWGS(position: Position, result: Position = Position()) : Position {
        //TODO Fix the the problem with coordinates conversion around the end of WGS84 coordinate system
        position.latitude.inDegrees.coerceIn(-88.0, 88.0).also { position.latitude = it.degrees }
        position.longitude.inDegrees.coerceAtLeast(-179.8).also { position.longitude = it.degrees }
        return HelmertTransformation.transform(position, fromWgsParameters, result)
    }

    fun transformToWGS(position: Position, result: Position = Position()) =
        HelmertTransformation.transform(position, toWgsParameters, result)

    override fun getGridRow(latitude: Angle) = when {
        latitude.inDegrees < - 88.0 -> 0
        latitude.inDegrees > 88.0 -> 45
        else -> floor(((latitude.inDegrees + 88.0) / 4.0) + 1.0).toInt().coerceAtMost(45)
    }

    override fun getTypeFor(resolution: Double) = when {
        resolution >= GK_MAX_RESOLUTION_1_000_000 -> GRATICULE_GK_1_000_000
        resolution >= GK_MAX_RESOLUTION_500_000 -> GRATICULE_GK_500_000
        resolution >= GK_MAX_RESOLUTION_200_000 -> GRATICULE_GK_200_000
        resolution >= GK_MAX_RESOLUTION_100_000 -> GRATICULE_GK_100_000
        resolution >= GK_MAX_RESOLUTION_50_000 -> GRATICULE_GK_50_000
        resolution >= GK_MAX_RESOLUTION_25_000 -> GRATICULE_GK_25_000
        else  -> GRATICULE_GK_10_000
    }

    fun getDistanceFor(type: String) = when(type) {
        GRATICULE_GK_1_000_000 -> GK_MAX_RESOLUTION_1_000_000
        GRATICULE_GK_500_000 -> GK_MAX_RESOLUTION_500_000
        GRATICULE_GK_200_000 -> GK_MAX_RESOLUTION_200_000
        GRATICULE_GK_100_000 -> GK_MAX_RESOLUTION_100_000
        GRATICULE_GK_50_000 -> GK_MAX_RESOLUTION_50_000
        GRATICULE_GK_25_000 -> GK_MAX_RESOLUTION_25_000
        else -> GK_MAX_RESOLUTION_10_000
    }

    override fun createGridTile(sector: Sector) = GKGraticuleTile(this, sector, GRATICULE_GK_1_000_000)

    fun addMetricLabel(label: Label) = metricLabels.addLabel(label)

    fun setMetricLabelScale(value: Int) {
        if (isZeroOrMinimalValue(value)) metricLabelScale = value
    }

    private fun isZeroOrMinimalValue(value: Int) =
        value == 0 || (value > 0 && metricLabelScale == 0) || (metricLabelScale != 0 && value < metricLabelScale)

    companion object {
        const val GRATICULE_GK_OVERVIEW = "Graticule.GK.Overview"
        const val GK_MAX_RESOLUTION_OVERVIEW = 15e5
        const val GRATICULE_GK_1_000_000 = "Graticule.GK.1_000_000"
        const val GK_MAX_RESOLUTION_1_000_000 = 1e6
        const val GRATICULE_GK_500_000 = "Graticule.GK.500_000"
        const val GK_MAX_RESOLUTION_500_000 = 5e5
        const val GRATICULE_GK_200_000 = "Graticule.GK.200_000"
        const val GK_MAX_RESOLUTION_200_000 = 2e5
        const val GRATICULE_GK_100_000 = "Graticule.GK.100_000"
        const val GK_MAX_RESOLUTION_100_000 = 1e5
        const val GRATICULE_GK_50_000 = "Graticule.GK.50_000"
        const val GK_MAX_RESOLUTION_50_000 = 35e3
        const val GRATICULE_GK_25_000 = "Graticule.GK.25_000"
        const val GK_MAX_RESOLUTION_25_000 = 15e3
        const val GRATICULE_GK_10_000 = "Graticule.GK.10_000"
        const val GK_MAX_RESOLUTION_10_000 = 8e3
        const val GK_METRIC_GRID_1000 = "GK.Metric.Grid.1000x1000"
        const val GK_METRIC_GRID_2000 = "GK.Metric.Grid.2000x2000"

        val MILLION_COOL_NAME = arrayOf("SZ", "SV", "SU", "ST", "SS", "SR", "SQ", "SP", "SO", "SN",
            "SM", "SL", "SK", "SJ", "SI", "SH", "SG", "SF", "SE", "SD", "SC", "SB", "SA","A", "B", "C", "D",
            "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "Z")

        val ENDING_200_000_MAP = arrayOf("І", "ІІ", "ІІІ", "IV", "V", "VI", "VII", "VIII", "IX", "X",
            "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX",
            "XXI", "XXII", "XXIII", "XXIV", "XXV", "XXVI", "XXVII", "XXVIII", "XXIX", "XXX",
            "XXXI", "XXXII", "XXXIII", "XXXIV", "XXXV", "XXXVI")
    }
}