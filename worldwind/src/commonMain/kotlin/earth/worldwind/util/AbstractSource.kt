package earth.worldwind.util

/**
 * AbstractSource instances are intended to be used as a key into a cache or other data structure that enables sharing of
 * loaded resources.
 */
abstract class AbstractSource protected constructor(protected val source: Any) {
    /**
     * Resource post-processing routine.
     */
    var postprocessor: ResourcePostprocessor? = null

    /**
     * @return generic image source as unrecognized object.
     */
    fun asUnrecognized() = source

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AbstractSource) return false
        if (source != other.source) return false
        return true
    }

    override fun hashCode() = source.hashCode()

    override fun toString() = source.toString()
}