package earth.worldwind

import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Renderable

enum class PickedPointMethod {
    DEPTH_UNPROJECTION,
    GEOMETRY_RAY_INTERSECTION,
}

data class PickedRenderablePoint(
    val pickedObject: PickedObject,
    val cartesianPoint: Vec3,
    val position: Position,
    val depth: Double,
    val method: PickedPointMethod,
) {
    val identifier get() = pickedObject.identifier
    val renderable: Renderable? get() = pickedObject.renderable
    val userObject get() = pickedObject.userObject
}
