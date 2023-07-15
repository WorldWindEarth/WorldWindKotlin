package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.Wgs84Projection

object HelmertTransformation {

    fun transform(position: Position, parameters: HelmertParameters, result: Position = Position()) =
        transform(position.latitude, position.longitude, position.altitude, parameters, result)

    fun transform(latitude: Angle, longitude: Angle, altitude: Double, parameters: HelmertParameters, result: Position = Position()): Position {
        val projection = Wgs84Projection()
        val fromGlobe = Globe(parameters.fromEllipsoid, projection)
        val toGlobe = Globe(parameters.toEllipsoid, projection)
        val fromCartesian = fromGlobe.geographicToCartesian(latitude, longitude, altitude, Vec3())
        val toCartesian = transform(fromCartesian, parameters)
        return toGlobe.cartesianToGeographic(toCartesian.x, toCartesian.y, toCartesian.z, result)
    }

    fun transform(point: Vec3, parameters: HelmertParameters, result: Vec3 = Vec3()) =
        transform(point.x, point.y, point.z, parameters, result)

    fun transform(x: Double, y: Double, z: Double, parameters: HelmertParameters, result: Vec3 = Vec3()) = result.set(
        x + -parameters.omegaZ * z + parameters.m * x + parameters.omegaX * y + parameters.dY,
        y + parameters.omegaY * z + -parameters.omegaX * x + parameters.m * y + parameters.dZ,
        z + parameters.m * z + parameters.omegaZ * x + -parameters.omegaY * y + parameters.dX
    )

}