package earth.worldwind.util

/**
 * Interface for resource download post-processing
 */
interface DownloadPostprocessor<T> {
    /**
     * Process resource according to specified algorithm implementation
     * @param resource original resource
     * @return processed resource
     */
    suspend fun process(resource: T): T
}