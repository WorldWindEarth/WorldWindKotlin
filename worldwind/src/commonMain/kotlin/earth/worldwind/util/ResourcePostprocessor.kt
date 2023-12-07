package earth.worldwind.util

/**
 * Interface for resource post-processing
 */
interface ResourcePostprocessor {
    /**
     * Process resource according to specified algorithm implementation
     * @param resource original resource
     * @return processed resource
     */
    suspend fun <Resource> process(resource: Resource): Resource
}