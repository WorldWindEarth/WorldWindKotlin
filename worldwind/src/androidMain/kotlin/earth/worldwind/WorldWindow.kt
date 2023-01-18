package earth.worldwind

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.MotionEvent
import android.view.SurfaceHolder
import earth.worldwind.frame.BasicFrameMetrics
import earth.worldwind.frame.Frame
import earth.worldwind.geom.Line
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Viewport
import earth.worldwind.gesture.SelectDragDetector
import earth.worldwind.navigator.NavigatorEventSupport
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.SynchronizedPool
import earth.worldwind.util.kgl.AndroidKgl
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Provides a WorldWind window that implements a virtual globe inside the Android view hierarchy. By default, World
 * Window is configured to display an ellipsoidal globe using the WGS 84 reference values.
 */
open class WorldWindow : GLSurfaceView, FrameCallback, GLSurfaceView.Renderer {
    /**
     * Main WorldWindow scope to execute jobs which should be cancelled on GL surface destroy
     */
    val mainScope = MainScope()
    /**
     * Main WorldWind engine, containing globe, terrain, renderable layers, camera, viewport and frame rendering logic
     */
    open val engine = WorldWind(
        AndroidKgl(), // Android OpenGL ES implementation
        RenderResourceCache(mainScope, context, RenderResourceCache.recommendedCapacity(context)),
        frameMetrics = BasicFrameMetrics()
    )
    /**
     * Current WorldWindow camera and gestures controller
     */
    var controller: WorldWindowController = BasicWorldWindowController(this)
    /**
     * Renderable selection and drag gestures detector. Assign [SelectDragDetector.callback] to handle events.
     */
    open val selectDragDetector = SelectDragDetector(this)
    /**
     * Helper class to process WorldWindow navigation event callbacks
     */
    open val navigatorEvents = NavigatorEventSupport(this)
    protected val framePool = SynchronizedPool<Frame>()
    protected val frameQueue = ConcurrentLinkedQueue<Frame>()
    protected val pickQueue = ConcurrentLinkedQueue<Frame>()
    protected var currentFrame: Frame? = null
    protected var isWaitingForRedraw = false

    companion object {
        protected const val MAX_FRAME_QUEUE_SIZE = 2
    }

    /**
     * Constructs a WorldWindow associated with the specified application context. This is the constructor to use when
     * creating a WorldWindow from code.
     */
    constructor(context: Context): super(context) { this.init(null) }

    /**
     * Constructs a WorldWindow associated with the specified application context and EGL configuration chooser. This is
     * the constructor to use when creating a WorldWindow from code.
     */
    constructor(context: Context, configChooser: EGLConfigChooser): super(context) { this.init(configChooser) }

    /**
     * Constructs a WorldWindow associated with the specified application context and attributes from an XML tag. This
     * constructor is included to provide support for creating WorldWindow from an Android XML layout file, and is not
     * intended to be used directly.
     * <br>
     * This is called when a view is being constructed from an XML file, supplying attributes that were specified in the
     * XML file. This version uses a default style of 0, so the only attribute values applied are those in the Context's
     * Theme and the given AttributeSet.
     */
    constructor(context: Context, attrs: AttributeSet): super(context, attrs) { this.init(null) }

    /**
     * Prepares this WorldWindow for drawing and event handling.
     *
     * @param configChooser optional argument for choosing an EGL configuration; may be null
     */
    protected open fun init(configChooser: EGLConfigChooser?) {
        // Set up to render on demand to an OpenGL ES 2.x context
        // TODO Investigate and use the EGL chooser submitted by jgiovino
        setEGLConfigChooser(configChooser)
        setEGLContextClientVersion(2) // must be called before setRenderer
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY // must be called after setRenderer
    }

    /**
     * Determines the WorldWind shapes displayed in a screen rectangle. The screen rectangle is interpreted as
     * coordinates in Android screen pixels relative to this view.
     *
     * @param x      the screen rectangle's X coordinate in Android screen pixels
     * @param y      the screen rectangle's Y coordinate in Android screen pixels
     * @param width  the screen rectangle's width in Android screen pixels
     * @param height the screen rectangle's height in Android screen pixels
     * @param pickCenter picks top shape and terrain in rectangle center as priority
     *
     * @return a deferred list of WorldWind shapes in the screen rectangle
     */
    fun pickAsync(x: Float, y: Float, width: Float = 0f, height: Float = 0f, pickCenter: Boolean = true): Deferred<PickedObjectList> {
        // Allocate a list in which to collect and return the picked objects.
        val pickedObjects = PickedObjectList()

        // Nothing can be picked if viewport is undefined.
        val viewport = engine.viewport
        if (viewport.isEmpty) return CompletableDeferred(pickedObjects)

        // Determine pick viewport
        val pickViewport = if (width != 0f && height != 0f) Viewport(
            floor(x).toInt(), viewport.height - ceil(y + height).toInt(), ceil(width).toInt(), ceil(height).toInt()
        ) else Viewport(x.roundToInt() - 1, viewport.height - y.roundToInt() - 1, 3, 3)
        if (!pickViewport.intersect(viewport)) return CompletableDeferred(pickedObjects)

        // Obtain a frame from the pool and render the frame, accumulating Drawables to process in the OpenGL thread.
        val pickDeferred = CompletableDeferred<PickedObjectList>()
        Frame.obtain(framePool).let { frame ->
            frame.pickedObjects = pickedObjects
            frame.pickDeferred = pickDeferred
            frame.pickViewport = pickViewport
            if (pickCenter) {
                // Compute the pick point in OpenGL screen coordinates, rounding to the nearest whole pixel. Nothing can be picked
                // if pick point is outside the WorldWindow's viewport.
                val px = pickViewport.x + pickViewport.width / 2.0
                val py = pickViewport.y + pickViewport.height / 2.0
                if(viewport.contains(px, py)) {
                    val pickRay = Line()
                    if (engine.rayThroughScreenPoint(px, viewport.height - py, pickRay)) {
                        frame.pickPoint = Vec2(px, py)
                        frame.pickRay = pickRay
                    }
                }
            }
            frame.isPickMode = true
            renderFrame(frame)
        }

        return pickDeferred
    }

    /**
     * Determines the WorldWind objects displayed at a screen point. The screen point is interpreted as coordinates in
     * Android screen pixels relative to this View.
     * <br>
     * If the screen point intersects any number of WorldWind shapes, the returned list contains a picked object
     * identifying the top shape at the screen point. This picked object includes the shape renderable (or its non-null
     * pick delegate) and the WorldWind layer that displayed the shape. Shapes which are either hidden behind another
     * shape at the screen point or hidden behind terrain at the screen point are omitted from the returned list.
     * Therefore, if the returned list contains a picked object identifying a shape, it is always marked as 'on top'.
     * <br>
     * If the screen point intersects the WorldWind terrain, the returned list contains a picked object identifying the
     * associated geographic position. If there are no shapes in the WorldWind scene between the terrain and the screen
     * point, the terrain picked object is marked as 'on top'.
     * <br>
     * This returns an empty list when nothing in the WorldWind scene intersects the screen point, when the screen
     * point is outside this View's bounds, or if the OpenGL thread displaying the WorldWindow's scene is paused (or
     * becomes paused while this method is executing).
     *
     * @param x the screen point's X coordinate in Android screen pixels
     * @param y the screen point's Y coordinate in Android screen pixels
     *
     * @return a list of WorldWind objects at the screen point
     */
    fun pick(x: Float, y: Float) = runBlocking { pickAsync(x, y).await() }

    /**
     * Determines the WorldWind shapes displayed in a screen rectangle. The screen rectangle is interpreted as
     * coordinates in Android screen pixels relative to this view.
     * <br>
     * If the screen rectangle intersects any number of WorldWind shapes, the returned list contains a picked object
     * identifying the all the top shapes in the rectangle. This picked object includes the shape renderable (or its
     * non-null pick delegate) and the WorldWind layer that displayed the shape. Shapes which are entirely hidden
     * behind another shape in the screen rectangle or are entirely hidden behind terrain in the screen rectangle are
     * omitted from the returned list.
     * <br>
     * This returns an empty list when no shapes in the WorldWind scene intersect the screen rectangle, when the screen
     * rectangle is outside this View's bounds, or if the OpenGL thread displaying the WorldWindow's scene is paused
     * (or becomes paused while this method is executing).
     *
     * @param x      the screen rectangle's X coordinate in Android screen pixels
     * @param y      the screen rectangle's Y coordinate in Android screen pixels
     * @param width  the screen rectangle's width in Android screen pixels
     * @param height the screen rectangle's height in Android screen pixels
     *
     * @return a list of WorldWind shapes in the screen rectangle
     */
    fun pickShapesInRect(x: Float, y: Float, width: Float, height: Float) = runBlocking {
        pickAsync(x, y, width, height, false).await()
    }

    /**
     * Request that this WorldWindow update its display. Prior changes to this WorldWindow's Camera, Globe and
     * Layers (including the contents of layers) are reflected on screen sometime after calling this method. May be
     * called from any thread.
     */
    fun requestRedraw() {
        mainScope.launch(Dispatchers.Main.immediate) {
            // Suppress duplicate redraw requests, request that occur while the WorldWindow is paused, and requests that
            // occur before we have an Android surface to draw to.
            if (!isWaitingForRedraw && !engine.viewport.isEmpty) {
                Choreographer.getInstance().postFrameCallback(this@WorldWindow)
                isWaitingForRedraw = true
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        // Skip frames when OpenGL thread has fallen two or more frames behind. Continue to request frame callbacks
        // until the OpenGL thread catches up.
        if (frameQueue.size >= MAX_FRAME_QUEUE_SIZE) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }

        // Allow subsequent redraw requests.
        isWaitingForRedraw = false

        // Obtain a frame from the pool and render the frame, accumulating Drawables to process in the OpenGL thread.
        // The frame is recycled by the OpenGL thread.
        try {
            renderFrame(Frame.obtain(framePool))
        } catch (e: Exception) {
            logMessage(
                ERROR, "WorldWindow", "doFrame",
                "Exception while rendering frame in Choreographer callback '$frameTimeNanos'", e
            )
        }
    }

    /**
     * Implements the GLSurfaceView.Renderer.onSurfaceChanged interface which is called on the GLThread when the surface
     * is created.
     */
    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        // Specify the default WorldWind OpenGL state.
        engine.setupDrawContext()

        // Clear the render resource cache on the main thread.
        mainScope.launch { engine.renderResourceCache.clear() }
    }

    /**
     * Implements the GLSurfaceView.Renderer.onSurfaceChanged interface which is called on the GLThread when the window
     * size changes.
     */
    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        // Set the WorldWind's new viewport dimensions.
        engine.setupViewport(width, height)

        // Store current screen density factor
        engine.densityFactor = context.resources.displayMetrics.density

        // Redraw this WorldWindow with the new viewport.
        requestRedraw()
    }

    /**
     * Implements the GLSurfaceView.Renderer.onDrawFrame interface which is called on the GLThread when rendering is
     * requested.
     */
    override fun onDrawFrame(unused: GL10) {
        // Remove and process pick the frame from the front of the pick queue, recycling it back into the pool. Continue
        // requesting frames on the OpenGL thread until the pick queue is empty. This is critical for correct operation.
        // All frames must be processed or threads waiting on a frame to finish may block indefinitely.
        pickQueue.poll()?.let { pickFrame ->
            try {
                engine.drawFrame(pickFrame)
            } catch (e: Exception) {
                logMessage(
                    ERROR, "WorldWindow", "onDrawFrame", "Exception while processing pick in OpenGL thread", e
                )
            } finally {
                pickFrame.recycle()
                requestRender()
            }
        }

        // Remove and switch to the frame at the front of the frame queue, recycling the previous frame back into the
        // pool. Continue requesting frames on the OpenGL thread until the frame queue is empty.
        frameQueue.poll()?.let { nextFrame ->
            currentFrame?.recycle()
            currentFrame = nextFrame
            requestRender()
        }

        // Process and display the Drawables accumulated in the last frame taken from the front of the queue. This frame
        // may be drawn multiple times if the OpenGL thread executes more often than the WorldWindow enqueues frames.
        try {
            currentFrame?.let{ engine.drawFrame(it) }
        } catch (e: Exception) {
            logMessage(
                ERROR, "WorldWindow", "onDrawFrame",
                "Exception while drawing frame in OpenGL thread", e
            )
        }
    }

    /**
     * Called immediately after the surface is first created, in which case the WorldWindow instance adds itself as a
     * listener to the [WorldWind.events] - a facility for broadcasting global redraw requests to active WorldWindows.
     *
     * @param holder the SurfaceHolder whose surface is being created
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)

        // Subscribe on events from WorldWind's global event bus.
        mainScope.launch {
            WorldWind.events.collect {
                when (it) {
                    is WorldWind.Event.RequestRedraw -> requestRedraw()
                    is WorldWind.Event.UnmarkResourceAbsent -> {
                        engine.renderResourceCache.absentResourceList.unmarkResourceAbsent(it.resourceId)
                    }
                }
            }
        }
    }

    /**
     * Called immediately before a surface is being destroyed, in which case the WorldWindow reset its internal state.
     *
     * @param holder the SurfaceHolder whose surface is being destroyed
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        super.surfaceDestroyed(holder)

        // Cancel all async jobs but keep scope reusable
        mainScope.coroutineContext.cancelChildren()

        // Reset any state associated with navigator events.
        navigatorEvents.reset()

        // Reset WorldWind rendering model state
        engine.reset()

        // Clear the frame queue and recycle pending frames back into the frame pool.
        clearFrameQueue()

        // Cancel any outstanding request redraw messages.
        Choreographer.getInstance().removeFrameCallback(this)
        isWaitingForRedraw = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Give the superclass first opportunity to handle the event.
        if (super.onTouchEvent(event)) return true

        try {
            if (!selectDragDetector.onTouchEvent(event)) {
                if (controller.onTouchEvent(event)) navigatorEvents.onTouchEvent(event)
            }
        } catch (e: Exception) {
            logMessage(
                ERROR, "WorldWindow", "onTouchEvent", "Exception while handling touch event '$event'", e
            )
        }

        // Always return true indicating that the event was handled, otherwise Android suppresses subsequent events.
        return true
    }

    protected open fun renderFrame(frame: Frame) {
        // Propagate redraw requests submitted during rendering. The render context provides a layer of indirection that
        // insulates rendering code from establishing a dependency on a specific WorldWindow.
        if (engine.renderFrame(frame)) requestRedraw()

        // Enqueue the frame for processing on the OpenGL thread as soon as possible and wake the OpenGL thread.
        if (frame.isPickMode) pickQueue.offer(frame) else frameQueue.offer(frame)
        requestRender()

        // Notify navigator change listeners when the modelview matrix associated with the frame has changed.
        if (!frame.isPickMode) navigatorEvents.onFrameRendered(frame.modelview, engine.globe.elevationModel.timestamp)
    }

    protected open fun clearFrameQueue() {
        // Clear the pick queue and recycle pending frames back into the frame pool. Mark the frame as done to ensure
        // that threads waiting for the frame to finish don't block indefinitely.
        while (true) pickQueue.poll()?.recycle() ?: break

        // Clear the frame queue and recycle pending frames back into the frame pool.
        while (true) frameQueue.poll()?.recycle() ?: break

        // Recycle the current frame back into the frame pool.
        currentFrame?.recycle()
        currentFrame = null
    }
}