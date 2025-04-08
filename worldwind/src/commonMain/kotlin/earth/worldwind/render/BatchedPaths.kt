package earth.worldwind.render

import earth.worldwind.shape.PathSet
import earth.worldwind.shape.LineSetAttributes
import earth.worldwind.shape.Path

class BatchedPaths(private val attributes: LineSetAttributes) {
    private val batches = mutableListOf<PathSet>()
    private val freeBatches =
        mutableListOf<PathSet>() // duplicate batches that aren't full here
    private val pathToBatch = mutableMapOf<Path, PathSet>()

    fun addPath(path: Path) {
        if (freeBatches.isEmpty()) {
            val newBatch = PathSet(attributes)
            newBatch.addPath(path)
            pathToBatch[path] = newBatch

            batches.add(newBatch)
            freeBatches.add(newBatch)
        } else {
            val freeBatch = freeBatches[0]
            freeBatch.addPath(path)
            pathToBatch[path] = freeBatch

            if (freeBatch.isFull()) freeBatches.remove(freeBatch)
        }
    }

    fun removePath(path: Path) {
        val batch = pathToBatch[path] ?: return
        if (batch.removePath(path) && !freeBatches.contains(batch)) freeBatches.add(batch)
        pathToBatch.remove(path)
    }

    fun render(rc: RenderContext) {
        for (batch in batches) {
            batch.render(rc)
        }
    }

    fun isAttributesEqual(other : LineSetAttributes) : Boolean{
        return attributes == other
    }
}