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
) : Sector(gudermannian(minLatPercent), gudermannian(maxLatPercent), minLongitude, maxLongitude) {

    override fun computeRow(tileDelta: Angle, latitude: Angle): Int {
        var row = floor(getY(latitude) * getTilesY(tileDelta)).toInt()
        // if latitude is at the end of the grid, subtract 1 from the computed row to return the last row
        if (latitude.inDegrees == maxLatitude.inDegrees) row -= 1
        return row
    }

    override fun computeLastRow(tileDelta: Angle, latitude: Angle): Int {
        var row = ceil(getY(latitude) * getTilesY(tileDelta) - 1).toInt()
        // if max latitude is in the first row, set the max row to 0
        if (latitude.inDegrees - minLatitude.inDegrees < tileDelta.inDegrees) row = 0
        return row
    }

    private fun getY(latitude: Angle) =
        (ln(tan(latitude.inRadians) + 1.0 / cos(latitude.inRadians)) / PI - minLatPercent) / (maxLatPercent - minLatPercent)

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