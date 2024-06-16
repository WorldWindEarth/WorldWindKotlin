package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.fromRadians
import earth.worldwind.geom.Sector
import kotlin.jvm.JvmStatic
import kotlin.math.*

open class MercatorSector(
    val minLatPercent: Double = -1.0, val maxLatPercent: Double = 1.0,
    minLongitude: Angle = Angle.NEG180, maxLongitude: Angle = Angle.POS180
): Sector(gudermannian(minLatPercent), gudermannian(maxLatPercent), minLongitude, maxLongitude) {

    override fun computeColumn(tileDelta: Angle, longitude: Angle) = floor(getX(longitude) * getTilesX(tileDelta)).toInt()

    override fun computeRow(tileDelta: Angle, latitude: Angle) = floor(getY(latitude) * getTilesY(tileDelta)).toInt()

    override fun computeLastColumn(tileDelta: Angle, longitude: Angle) = ceil(getX(longitude) * getTilesX(tileDelta) - 1).toInt()

    override fun computeLastRow(tileDelta: Angle, latitude: Angle) = ceil(getY(latitude) * getTilesY(tileDelta) - 1).toInt()

    private fun getX(longitude: Angle) = (longitude.inDegrees + 180.0) / 360.0

    private fun getY(latitude: Angle) = (1.0 + ln(tan(latitude.inRadians) + 1.0 / cos(latitude.inRadians)) / PI) / 2.0

    private fun getTilesX(tileDelta: Angle) = deltaLongitude.inDegrees / tileDelta.inDegrees

    private fun getTilesY(tileDelta: Angle) = deltaLatitude.inDegrees / tileDelta.inDegrees

    companion object {
        @JvmStatic
        fun fromDegrees(
            minLatPercent: Double, maxLatPercent: Double, minLonDegrees: Double, maxLonDegrees: Double
        ) = MercatorSector(
            minLatPercent, maxLatPercent, fromDegrees(minLonDegrees), fromDegrees(maxLonDegrees)
        )

        @JvmStatic
        fun fromRadians(
            minLatPercent: Double, maxLatPercent: Double, minLonRadians: Double, maxLonRadians: Double
        ) = MercatorSector(
            minLatPercent, maxLatPercent, fromRadians(minLonRadians), fromRadians(maxLonRadians)
        )

        @JvmStatic
        fun fromSector(sector: Sector) = MercatorSector(
            gudermannianInverse(sector.minLatitude), gudermannianInverse(sector.maxLatitude),
            sector.minLongitude, sector.maxLongitude
        )

        @JvmStatic
        fun gudermannianInverse(latitude: Angle) = ln(tan(PI / 4.0 + latitude.inRadians / 2.0)) / PI

        @JvmStatic
        fun gudermannian(percent: Double) = fromRadians(atan(sinh(percent * PI)))
    }
}