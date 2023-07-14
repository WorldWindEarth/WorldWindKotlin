package earth.worldwind.geom.coords

import earth.worldwind.geom.Ellipsoid

enum class HelmertParameters(
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
    UCS2000_WGS84(24.3234, -121.3708, -75.8275, 0.0, 0.0, 0.0, -1.74e-9, Ellipsoid.Krasovsky, Ellipsoid.WGS84),
    WGS84_UCS2000(-24.3234, 121.3708, 75.8275, 0.0, 0.0, 0.0, 1.74e-9, Ellipsoid.WGS84, Ellipsoid.Krasovsky),
    SK42_WGS84(23.92, -141.27, -80.9, 0.0, 0.0, 0.0, 0.0, Ellipsoid.Krasovsky, Ellipsoid.WGS84),
    WGS84_SK42(-23.92, 141.27, 80.9, 0.0, 0.0, 0.0, 0.0, Ellipsoid.WGS84, Ellipsoid.Krasovsky),
}