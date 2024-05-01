package earth.worldwind.layer.graticule.gk

import earth.worldwind.geom.Angle
import earth.worldwind.geom.coords.GKCoord
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_100_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_1_000_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_200_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_25_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_500_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_50_000
import kotlin.jvm.JvmStatic

object GKLayerHelper {
    private const val Z_ZONE_LATITUDE = 88
    private const val MAX_LONGITUDE = 180
    private const val LATITUDE_1M_MAP = 4
    private const val LONGITUDE_1M_MAP = 6
    private const val LATITUDE_200K_MAP = 0.4 / 60.0 * 100.0 // 40 minutes
    private const val LATITUDE_100K_MAP = 0.2 / 60.0 * 100.0 // 20 minutes
    private const val LATITUDE_50K_MAP = 0.1 / 60.0 * 100.0 // 10 minutes
    private const val LATITUDE_25K_MAP = 0.05 / 60.0 * 100.0 // 10 minutes
    private const val LATITUDE_10K_MAP = 0.025 / 60.0 * 100.0 // 10 minutes

    /**
     * Get zone number by Gauss-Kruger longitude
     *
     * @param longitude Gauss-Kruger longitude
     * @return Corresponding zone number
     */
    @JvmStatic
    fun getZone(longitude: Angle) = if (longitude.inDegrees >= 0) longitude.inDegrees.toInt() / 6 + 1
    else (longitude.inDegrees + 180.0).toInt() / 6 + 31

    /**
     * The method returns coordinates of intersection lines.
     * The method doesn't check that the lines have an intersection
     * and will work incorrectly if the intersection is absent.
     *
     * The method returns GKCoordinate of intersection the lines or if
     * lines are parallel the method returns null
     */
    @JvmStatic
    fun intersect(
        x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double, x4: Double, y4: Double
    ): GKCoord? {
        val a1 = y2 - y1
        val b1 = x1 - x2
        val c1 = a1 * x1 + b1 * y1

        val a2 = y4 - y3
        val b2 = x3 - x4
        val c2 = a2 * x3 + b2 * y3

        val determinant = a1 * b2 - a2 * b1

        return if (determinant != 0.0) {
            val x = (b2 * c1 - b1 * c2) / determinant
            val y = (a1 * c2 - a2 * c1) / determinant
            GKCoord.fromXY(x, y)
        } else null // The lines are parallel.
    }

    @JvmStatic
    fun getNameByCoord(
        latitude: Angle, longitude: Angle, type: String = GRATICULE_GK_1_000_000, previousScaleName: String = ""
    ): String = when(type) {
        GRATICULE_GK_1_000_000 -> getMillionNameByCoord(latitude, longitude)
        GRATICULE_GK_500_000 -> previousScaleName.ifEmpty { getMillionNameByCoord (latitude, longitude) } +
                get500kPrefix(latitude, longitude)
        GRATICULE_GK_200_000 -> getMillionNameByCoord(latitude, longitude) + get200kPrefix(latitude, longitude)
        GRATICULE_GK_100_000 -> getMillionNameByCoord(latitude, longitude) + get100kPrefix(latitude, longitude)
        GRATICULE_GK_50_000 -> previousScaleName.ifEmpty { getNameByCoord (latitude, longitude, GRATICULE_GK_100_000) } +
                get50kPrefix(latitude, longitude)
        GRATICULE_GK_25_000 -> previousScaleName.ifEmpty { getNameByCoord (latitude, longitude, GRATICULE_GK_50_000) } +
                get25kPrefix(latitude, longitude)
        else -> previousScaleName.ifEmpty { getNameByCoord (latitude, longitude, GRATICULE_GK_25_000) } +
                get10kPrefix(latitude, longitude)
    }

    @JvmStatic
    private fun get500kPrefix(latitude: Angle, longitude: Angle): String {
        val rowFromTop = 1 -((latitude.inDegrees + Z_ZONE_LATITUDE) % LATITUDE_1M_MAP / 2).toInt()
        val col = ((longitude.inDegrees + MAX_LONGITUDE) % LONGITUDE_1M_MAP / 3 ).toInt()
        val num = rowFromTop * 2 + col
        return when (num) {
            0 -> "-A"
            1 -> "-Б"
            2 -> "-В"
            else -> "-Г"
        }
    }

    @JvmStatic
    private fun get200kPrefix(latitude: Angle, longitude: Angle): String {
        val rowFromTop = 5 -((latitude.inDegrees + Z_ZONE_LATITUDE) % LATITUDE_1M_MAP / LATITUDE_200K_MAP).toInt()
        val col = ((longitude.inDegrees + MAX_LONGITUDE) % LONGITUDE_1M_MAP).toInt()
        val num = rowFromTop * LONGITUDE_1M_MAP + col
        val romaNumber = GKGraticuleLayer.ENDING_200_000_MAP[num]
        return "-$romaNumber"
    }

    @JvmStatic
    private fun get100kPrefix(latitude: Angle, longitude: Angle): String {
        val rowFromTop = 11 - ((latitude.inDegrees + Z_ZONE_LATITUDE) % LATITUDE_1M_MAP / LATITUDE_100K_MAP).toInt()
        val col = (((longitude.inDegrees + MAX_LONGITUDE) % LONGITUDE_1M_MAP) / 0.5).toInt() + 1
        val num = rowFromTop * 12 + col
        return "-$num"
    }

    @JvmStatic
    private fun get50kPrefix(latitude: Angle, longitude: Angle): String {
        var countOf100Maps = ((latitude.inDegrees + Z_ZONE_LATITUDE)/ LATITUDE_100K_MAP).toInt()
        val rowFromTop = 1 -((latitude.inDegrees + Z_ZONE_LATITUDE - countOf100Maps * LATITUDE_100K_MAP)/ LATITUDE_50K_MAP).toInt()
        countOf100Maps = ((longitude.inDegrees + MAX_LONGITUDE) / 0.5).toInt()
        val col = (((longitude.inDegrees + MAX_LONGITUDE) - countOf100Maps * 0.5) / 0.25).toInt()
        val num = rowFromTop * 2 + col
        return when(num) {
            0 -> "-A"
            1 -> "-Б"
            2 -> "-В"
            else -> "-Г"
        }
    }

    @JvmStatic
    private fun get25kPrefix(latitude: Angle, longitude: Angle): String {
        var countOf50Maps = ((latitude.inDegrees + Z_ZONE_LATITUDE)/ LATITUDE_50K_MAP).toInt()
        val rowFromTop = 1 -(((latitude.inDegrees + Z_ZONE_LATITUDE) - countOf50Maps * LATITUDE_50K_MAP)/ LATITUDE_25K_MAP).toInt()
        countOf50Maps = ((longitude.inDegrees + MAX_LONGITUDE) / 0.25).toInt()
        val col = (((longitude.inDegrees + MAX_LONGITUDE) - countOf50Maps * 0.25) / 0.125).toInt()
        val num = rowFromTop * 2 + col
        return when(num) {
            0 -> "-a"
            1 -> "-б"
            2 -> "-в"
            else -> "-г"
        }
    }

    @JvmStatic
    private fun get10kPrefix(latitude: Angle, longitude: Angle): String {
        var countOf50Maps = ((latitude.inDegrees + Z_ZONE_LATITUDE)/ LATITUDE_25K_MAP).toInt()
        val rowFromTop = 1 - ((latitude.inDegrees + Z_ZONE_LATITUDE - countOf50Maps * LATITUDE_25K_MAP) / LATITUDE_10K_MAP).toInt()
        countOf50Maps = ((longitude.inDegrees + MAX_LONGITUDE) / 0.125).toInt()
        val col = ((longitude.inDegrees + MAX_LONGITUDE - countOf50Maps * 0.125) / 0.0625).toInt() + 1
        val num = rowFromTop * 2 + col
        return "-$num"
    }

    @JvmStatic
    private fun getMillionNameByCoord(latitude: Angle, longitude: Angle): String {
        if (latitude.inDegrees >= Z_ZONE_LATITUDE) return GKGraticuleLayer.MILLION_COOL_NAME[45]
        if (latitude.inDegrees < -Z_ZONE_LATITUDE) return GKGraticuleLayer.MILLION_COOL_NAME[0]
        //TODO Add for 12 and 24 grade zone.
        val col = getMillionColumnIndex(longitude.inDegrees)
        return GKGraticuleLayer.MILLION_COOL_NAME[getMillionRowIndex(latitude.inDegrees)] + "-" +
                if (col< 9) "0" + (col + 1) else (col + 1)
    }

    @JvmStatic
    fun getMillionRowIndex(latitude: Double) = ((latitude + Z_ZONE_LATITUDE + LATITUDE_1M_MAP) / LATITUDE_1M_MAP).toInt()

    @JvmStatic
    fun getMillionColumnIndex(longitude: Double) =
        ((longitude + MAX_LONGITUDE) / LONGITUDE_1M_MAP).toInt().coerceAtMost(59)
}