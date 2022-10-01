package earth.worldwind.shape

/**
 * Interface to control a shape's highlighting. Shapes implementing this interface have their own highlighting behaviors
 * and attributes and the means for setting them.
 */
interface Highlightable {
    /**
     * Indicates whether the shape is highlighted.
     */
    var isHighlighted: Boolean
}