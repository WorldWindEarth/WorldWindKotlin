package earth.worldwind.shape

import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe

/**
 * Represents an intersection result.
 */
data class Intersection(val position: Position, val distance: Double)

/**
 * Provides a common API for 3D shapes that support ray intersection.
 */
interface IRayIntersectable {
    /**
     * Determines if the given ray intersects the shape.
     *
     * @param ray The ray to test for intersection. Typically, a line from the pick event.
     * @param globe The Globe for calculations.
     * @returns Array of Intersection objects sorted by distance from ray origin, or empty array if no intersections.
     */
    fun rayIntersections(ray: Line, globe: Globe): Array<Intersection>
}