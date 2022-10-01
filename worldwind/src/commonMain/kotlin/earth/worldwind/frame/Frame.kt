package earth.worldwind.frame

import earth.worldwind.PickedObjectList
import earth.worldwind.draw.DrawableQueue
import earth.worldwind.geom.Line
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Viewport
import earth.worldwind.util.Pool
import kotlinx.coroutines.CompletableDeferred
import kotlin.jvm.JvmStatic

open class Frame {
    val viewport = Viewport()
    val projection = Matrix4()
    val modelview = Matrix4()
//    val infiniteProjection = Matrix4()
    val drawableQueue = DrawableQueue()
    val drawableTerrain = DrawableQueue()
    var pickedObjects: PickedObjectList? = null
    var pickDeferred: CompletableDeferred<PickedObjectList>? = null
    var pickViewport: Viewport? = null
    var pickPoint: Vec2? = null
    var pickRay: Line? = null
    var isPickMode = false
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
        drawableQueue.clearDrawables()
        drawableTerrain.clearDrawables()
        pickedObjects?.let{ pickDeferred?.complete(it) } // Complete deferred pick if available
        pickedObjects = null
        pickDeferred = null
        pickViewport = null
        pickPoint = null
        pickRay = null
        isPickMode = false
        pool?.release(this) // return this instance to the pool
        pool = null
    }
}