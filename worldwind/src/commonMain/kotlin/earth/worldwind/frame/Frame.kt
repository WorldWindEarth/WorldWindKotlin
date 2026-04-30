package earth.worldwind.frame

import earth.worldwind.PickedObjectList
import earth.worldwind.draw.DrawableQueue
import earth.worldwind.draw.UploadQueue
import earth.worldwind.geom.Line
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.Viewport
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.render.program.DepthToColorProgram
import earth.worldwind.util.Pool
import kotlinx.coroutines.CompletableDeferred
import kotlin.jvm.JvmStatic

open class Frame {
    val viewport = Viewport()
    val projection = Matrix4()
    val modelview = Matrix4()
    /**
     * World-space (Cartesian) unit vector pointing toward the light source. Captured from
     * [earth.worldwind.render.RenderContext.lightDirection] at the end of the frame's render
     * phase (after layers, including AtmosphereLayer, have had a chance to override it) and
     * propagated to [earth.worldwind.draw.DrawContext.lightDirection] for the draw phase.
     */
    val lightDirection = Vec3(0.0, 0.0, 1.0)
    /**
     * Per-frame snapshot of the cascaded shadow-map state. Pre-allocated and owned by this
     * Frame: on Android, render runs on the main thread and draw on the GL thread, so we
     * cannot share [ShadowLayer]'s in-place-mutated scratch instance by reference -
     * [ShadowState.copyFrom] populates this snapshot at the end of render. Valid only when
     * [hasShadowState] is `true`.
     */
    val shadowState: ShadowState = ShadowState()
    /** `true` when [ShadowLayer] populated [shadowState] this frame. */
    var hasShadowState: Boolean = false
//    val infiniteProjection = Matrix4()
    val uploadQueue = UploadQueue()
    val drawableQueue = DrawableQueue()
    val drawableTerrain = DrawableQueue()
    var pickedObjects: PickedObjectList? = null
    var pickDeferred: CompletableDeferred<PickedObjectList>? = null
    var pickViewport: Viewport? = null
    var pickPoint: Vec2? = null
    var pickRay: Line? = null
    var depthToColorProgram: DepthToColorProgram? = null
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
        lightDirection.set(0.0, 0.0, 1.0)
        hasShadowState = false
//        infiniteProjection.setToIdentity()
        uploadQueue.clearUploads()
        drawableQueue.clearDrawables()
        drawableTerrain.clearDrawables()
        pickedObjects?.let { pickDeferred?.complete(it) } // Complete deferred pick if available
        pickedObjects = null
        pickDeferred = null
        pickViewport = null
        pickPoint = null
        pickRay = null
        depthToColorProgram = null
        isPickMode = false
        pool?.release(this) // return this instance to the pool
        pool = null
    }
}