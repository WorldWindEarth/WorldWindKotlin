package earth.worldwind.geom

/**
 * Oblate ellipsoid with semi-major axis and inverse flattening.
 */
enum class Ellipsoid(
    /**
     * One half of the ellipsoid's major axis length in meters, which runs through the center to opposite points on the
     * equator.
     */
    val semiMajorAxis: Double,
    /**
     * Measure of the ellipsoid's compression. Indicates how much the ellipsoid's semi-minor axis is compressed relative
     * to the semi-major axis. Expressed as `1/f`, where `f = (a - b) / a`, given the semi-major axis `a` and the semi-minor axis `b`.
     */
    val inverseFlattening: Double
) {
    /**
     * WGS 84 reference ellipsoid for Earth. The ellipsoid's semi-major axis and inverse flattening factor are
     * configured according to the WGS 84 reference system (aka WGS 1984, EPSG:4326). WGS 84 reference values taken from
     * [here](http://earth-info.nga.mil/GandG/publications/NGA_STND_0036_1_0_0_WGS84/NGA.STND.0036_1.0.0_WGS84.pdf).
     */
    WGS84(6378137.0, 298.257223563);
    /**
     * Computes this ellipsoid's semi-minor length axis in meters. The semi-minor axis is one half of the ellipsoid's
     * minor axis, which runs through the center to opposite points on the poles.
     */
    val semiMinorAxis: Double get() {
        val f = 1 / inverseFlattening
        return semiMajorAxis * (1 - f)
    }
    /**
     * Computes this ellipsoid's eccentricity squared. The returned value is equivalent to `2*f - f*f`,
     * where `f` is this ellipsoid's flattening.
     */
    val eccentricitySquared: Double get() {
        val f = 1 / inverseFlattening
        return 2 * f - f * f
    }

    override fun toString() = "Ellipsoid(semiMajorAxis=$semiMajorAxis, inverseFlattening=$inverseFlattening)"
}