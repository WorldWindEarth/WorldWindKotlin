package earth.worldwind.render

import earth.worldwind.shape.LineSetAttributes
import earth.worldwind.shape.Path

class PathBatchRenderer : BatchRenderer {

    private val batchedLines = mutableListOf<BatchedPaths>()
    private val attributesToBatchedPaths = mutableMapOf<LineSetAttributes, BatchedPaths>()
    private val pathToBatchedPaths = mutableMapOf<Path, BatchedPaths>()

    private fun addPathToBatch(path: Path) {
        val pathAttributes = LineSetAttributes(path)
        var batch = attributesToBatchedPaths[pathAttributes]
        if(batch == null) {
            batch = BatchedPaths(pathAttributes)
            batchedLines.add(batch)
            attributesToBatchedPaths[pathAttributes] = batch
        }
        batch.addPath(path)
        pathToBatchedPaths[path] = batch
    }

    private fun removePathFromBatch(path : Path) {
        val currentBatch = pathToBatchedPaths[path] ?: return
        currentBatch.removePath(path)
        pathToBatchedPaths.remove(path)
    }

    private fun updatePath(path: Path) {
        val pathAttributes = LineSetAttributes(path)
        val currentBatch = pathToBatchedPaths[path] ?: return
        if (!currentBatch.isAttributesEqual(pathAttributes)) {
            removePathFromBatch(path)
            addPathToBatch(path)
        }
    }

    /* return true if shape was batched */
    override fun addOrUpdateRenderable(renderable: Renderable) : Boolean {
        if(renderable !is Path)
            return false

        val pathWasBatched = pathToBatchedPaths[renderable] != null
        if (!pathWasBatched) {
            renderable.reset()
            addPathToBatch(renderable)
        } else if (pathWasBatched) {
            updatePath(renderable)
        }

        return true
    }

    override fun removeRenderable(renderable : Renderable) {
        if(renderable !is Path)
            return

        removePathFromBatch(renderable)
    }

    override fun render(rc : RenderContext) {
        // Remove shapes that wasn't requested to be drawn this frame
        val pathsToRemove = pathToBatchedPaths.keys.filter { it.lastRequestedFrameIndex != rc.frameIndex }
        for(path in pathsToRemove) {
            if(path.lastRequestedFrameIndex != rc.frameIndex) {
                removePathFromBatch(path)
            }
        }

        for (batch in batchedLines) {
            batch.render(rc)
        }
    }

    override fun clear() {
        batchedLines.clear()
        attributesToBatchedPaths.clear()
        pathToBatchedPaths.clear()
    }
}