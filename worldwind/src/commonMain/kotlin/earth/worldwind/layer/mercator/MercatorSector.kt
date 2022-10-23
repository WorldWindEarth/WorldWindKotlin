package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.fromRadians
import earth.worldwind.geom.Sector
import kotlin.jvm.JvmStatic
import kotlin.math.*

open class MercatorSector(
    val minLatPercent: Double, val maxLatPercent: Double, minLongitude: Angle, maxLongitude: Angle
): Sector(gudermannian(minLatPercent), gudermannian(maxLatPercent), minLongitude, maxLongitude) {
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