package earth.worldwind.frame

import earth.worldwind.PickedObject
import earth.worldwind.PickedObjectList
import earth.worldwind.PickedRenderablePoint
import earth.worldwind.draw.DrawableQueue
import earth.worldwind.draw.UploadQueue
import earth.worldwind.geom.Line
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Viewport
import earth.worldwind.render.Renderable
import earth.worldwind.util.Pool
import kotlinx.coroutines.CompletableDeferred
import kotlin.jvm.JvmStatic

open class Frame {
    val viewport = Viewport()
    val projection = Matrix4()
    val modelview = Matrix4()
//    val infiniteProjection = Matrix4()
    val uploadQueue = UploadQueue()
    val drawableQueue = DrawableQueue()
    val drawableTerrain = DrawableQueue()
    var pickedObjects: PickedObjectList? = null
    var pickDeferred: CompletableDeferred<PickedObjectList>? = null
    var pickViewport: Viewport? = null
    var pickPoint: Vec2? = null
    var pickRay: Line? = null
    var renderableFilter: Renderable? = null
    var pointPickedObject: PickedObject? = null
    var pointPickDeferred: CompletableDeferred<PickedRenderablePoint?>? = null
    var pointPickedRenderablePoint: PickedRenderablePoint? = null
    var forceDepthPointPick = false
    var isPickMode = false
    var isDepthPickingMode = false
    private var pool: Pool<Frame>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<Frame>): Frame {
            val instance = pool.acquire() ?: Frame()  // get an instance from the pool
            instance.pool = pool
            return instance
        }
    }

    open fun recycle() {
        viewport.setEmpty()
        projection.setToIdentity()
        modelview.setToIdentity()
//        infiniteProjection.setToIdentity()
        uploadQueue.clearUploads()
        drawableQueue.clearDrawables()
        drawableTerrain.clearDrawables()
        pickedObjects?.let { pickDeferred?.complete(it) } // Complete deferred pick if available
        pointPickDeferred?.complete(pointPickedRenderablePoint)
        pickedObjects = null
        pickDeferred = null
        pickViewport = null
        pickPoint = null
        pickRay = null
        renderableFilter = null
        pointPickedObject = null
        pointPickDeferred = null
        pointPickedRenderablePoint = null
        forceDepthPointPick = false
        isPickMode = false
        isDepthPickingMode = false
        pool?.release(this) // return this instance to the pool
        pool = null
    }
}
