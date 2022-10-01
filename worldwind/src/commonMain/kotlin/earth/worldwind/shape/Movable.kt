package earth.worldwind.shape

import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe

interface Movable {
    /**
     * A position associated with the object that indicates its aggregate geographic position. The chosen position
     * varies among implementers of this interface. For objects defined by a list of positions, the reference position
     * is typically the first position in the list. For symmetric objects the reference position is often the center of
     * the object. In many cases the object's reference position may be explicitly specified by the application.
     *
     * @return the object's reference position, or null if no reference position is available.
     */
    val referencePosition: Position?

    /**
     * Move the shape over the globe's surface while maintaining its original azimuth, its orientation relative to
     * North.
     *
     * @param globe    the globe on which to move the shape.
     * @param position the new position of the shape's reference position.
     */
    fun moveTo(globe: Globe, position: Position)
}