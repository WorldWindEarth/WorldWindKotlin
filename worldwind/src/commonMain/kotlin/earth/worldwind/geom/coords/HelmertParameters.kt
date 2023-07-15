package earth.worldwind.geom.coords

import earth.worldwind.geom.Ellipsoid
import kotlin.jvm.JvmStatic

data class HelmertParameters(
    // Linear transformation elements, in metres
    val dX: Double,
    val dY: Double,
    val dZ: Double,

    // Angular transformation elements, in arc-seconds
    val omegaX: Double,
    val omegaY: Double,
    val omegaZ: Double,

    // Scale differential
    val m: Double,

    // Ellipsoid
    val fromEllipsoid: Ellipsoid,
    val toEllipsoid: Ellipsoid
) {
    companion object {
        @JvmStatic val UCS2000_WGS84 = HelmertParameters(
            24.3234, -121.3708, -75.8275,
            0.0, 0.0, 0.0,
            -1.74e-9, Ellipsoid.Krasovsky, Ellipsoid.WGS84
        )
        @JvmStatic val WGS84_UCS2000 = HelmertParameters(
            -24.3234, 121.3708, 75.8275,
            0.0, 0.0, 0.0,
            1.74e-9, Ellipsoid.WGS84, Ellipsoid.Krasovsky
        )
        @JvmStatic val SK42_WGS84 = HelmertParameters(
            23.92, -141.27, -80.9,
            0.0, 0.0, 0.0,
            0.0, Ellipsoid.Krasovsky, Ellipsoid.WGS84
        )
        @JvmStatic val WGS84_SK42 = HelmertParameters(
            -23.92, 141.27, 80.9,
            0.0, 0.0, 0.0,
            0.0, Ellipsoid.WGS84, Ellipsoid.Krasovsky
        )
    }
}