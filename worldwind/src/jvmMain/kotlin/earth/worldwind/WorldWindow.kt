package earth.worldwind

import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLJPanel
import earth.worldwind.frame.Frame
import earth.worldwind.geom.Line
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Viewport
import earth.worldwind.gesture.SelectDragDetector
import earth.worldwind.layer.Layer
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.SynchronizedPool
import earth.worldwind.util.kgl.JoglKgl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Provides a WorldWind window that implements a virtual globe inside the Swing view hierarchy.
 * By default, WorldWindow is configured to display an ellipsoidal globe using the WGS 84
 * reference values.
 *
 * @param renderResourceCache render resource cache shared with the engine lifecycle
 * @param capabilities OpenGL capabilities used to create the internal [GLJPanel]
 * @param layerFactory DSL block invoked during initialization to populate [WorldWind.layers]
 */
open class WorldWindow @JvmOverloads constructor(
    protected val renderResourceCache: RenderResourceCache = RenderResourceCache(),
    capabilities: GLCapabilities = defaultCapabilities(),
    val layerFactory: WorldWindowLayerFactoryScope.() -> Unit
) : JPanel(BorderLayout()), GLEventListener, WorldWind.EventListener {
    /**
     * Main WorldWindow scope to execute jobs bound to render resource lifecycle.
     */
    val mainScope get() = renderResourceCache.mainScope

    /**
     * Swing OpenGL panel that presents rendered frames.
     */
    val glPanel = GLJPanel(capabilities)

    /**
     * Main WorldWind engine, containing globe, terrain, renderable layers, camera,
     * viewport and frame rendering logic.
     */
    lateinit var engine: WorldWind
        protected set

    /**
     * Current WorldWindow camera and gestures controller.
     */
    var controller: WorldWindowController = BasicWorldWindowController(this)

    /**
     * Renderable selection and drag gestures detector. Assign [SelectDragDetector.callback]
     * to handle events.
     */
    open val selectDragDetector = SelectDragDetector(this)

    protected val framePool = SynchronizedPool<Frame>()
    protected val frameQueue = ConcurrentLinkedQueue<Frame>()
    protected val pickQueue = ConcurrentLinkedQueue<Frame>()
    protected var currentFrame: Frame? = null
    protected var eventsJob: Job? = null

    @Volatile
    protected var isWaitingForRedraw = false

    init {
        add(glPanel, BorderLayout.CENTER)
        isFocusable = true
        glPanel.isFocusable = true
        glPanel.addGLEventListener(this)

        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dispatchMouseEvent(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                dispatchMouseEvent(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                dispatchMouseEvent(e)
            }

            override fun mouseExited(e: MouseEvent) {
                dispatchMouseEvent(e)
            }
        }
        glPanel.addMouseListener(mouseAdapter)
        glPanel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                dispatchMouseEvent(e)
            }

            override fun mouseMoved(e: MouseEvent) {
                dispatchMouseEvent(e)
            }
        })
        glPanel.addMouseWheelListener { e ->
            dispatchWheelEvent(e)
        }
    }

    /**
     * Determines the WorldWind shapes displayed in a screen rectangle. The screen rectangle is
     * interpreted as coordinates in Swing screen pixels relative to this view.
     *
     * @param x the screen rectangle's X coordinate in Swing screen pixels
     * @param y the screen rectangle's Y coordinate in Swing screen pixels
     * @param width the screen rectangle's width in Swing screen pixels
     * @param height the screen rectangle's height in Swing screen pixels
     * @param pickCenter picks top shape and terrain in rectangle center as priority
     *
     * @return a deferred list of WorldWind shapes in the screen rectangle
     */
    fun pickAsync(x: Float, y: Float, width: Float = 0f, height: Float = 0f, pickCenter: Boolean = true): Deferred<PickedObjectList> {
        val pickedObjects = PickedObjectList()
        if (!::engine.isInitialized) return CompletableDeferred(pickedObjects)

        val viewport = engine.viewport
        if (viewport.isEmpty) return CompletableDeferred(pickedObjects)

        val pickViewport = if (width != 0f && height != 0f) Viewport(
            floor(x).toInt(), viewport.height - ceil(y + height).toInt(), ceil(width).toInt(), ceil(height).toInt()
        ) else Viewport(x.roundToInt() - 1, viewport.height - y.roundToInt() - 1, 3, 3)
        if (!pickViewport.intersect(viewport)) return CompletableDeferred(pickedObjects)

        val pickDeferred = CompletableDeferred<PickedObjectList>()
        Frame.obtain(framePool).let { frame ->
            frame.pickedObjects = pickedObjects
            frame.pickDeferred = pickDeferred
            frame.pickViewport = pickViewport
            if (pickCenter) {
                val px = pickViewport.x + pickViewport.width / 2.0
                val py = pickViewport.y + pickViewport.height / 2.0
                if (viewport.contains(px, py)) {
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
     * Determines the WorldWind objects displayed at a screen point. The screen point is
     * interpreted as coordinates in Swing screen pixels relative to this view.
     * <br>
     * If the screen point intersects any number of WorldWind shapes, the returned list contains a
     * picked object identifying the top shape at the screen point. This picked object includes the
     * shape renderable (or its non-null pick delegate) and the WorldWind layer that displayed the
     * shape. Shapes that are hidden behind another shape or terrain at the screen point are
     * omitted from the returned list.
     * <br>
     * If the screen point intersects the WorldWind terrain, the returned list contains a picked
     * object identifying the associated geographic position. If there are no shapes in the
     * WorldWind scene between the terrain and the screen point, the terrain picked object is
     * marked as "on top".
     *
     * @param x the screen point's X coordinate in Swing screen pixels
     * @param y the screen point's Y coordinate in Swing screen pixels
     *
     * @return a list of WorldWind objects at the screen point
     */
    fun pick(x: Float, y: Float) = runBlocking { pickAsync(x, y).await() }

    /**
     * Determines the WorldWind shapes displayed in a screen rectangle. The screen rectangle is
     * interpreted as coordinates in Swing screen pixels relative to this view.
     * <br>
     * If the screen rectangle intersects any number of WorldWind shapes, the returned list
     * contains a picked object identifying all the top shapes in the rectangle. This picked object
     * includes the shape renderable (or its non-null pick delegate) and the WorldWind layer that
     * displayed the shape. Shapes that are entirely hidden behind another shape or terrain in the
     * screen rectangle are omitted from the returned list.
     *
     * @param x the screen rectangle's X coordinate in Swing screen pixels
     * @param y the screen rectangle's Y coordinate in Swing screen pixels
     * @param width the screen rectangle's width in Swing screen pixels
     * @param height the screen rectangle's height in Swing screen pixels
     *
     * @return a list of WorldWind shapes in the screen rectangle
     */
    fun pickShapesInRect(x: Float, y: Float, width: Float, height: Float) = runBlocking {
        pickAsync(x, y, width, height, false).await()
    }

    /**
     * Request that this WorldWindow update its display. Prior changes to this WorldWindow's
     * camera, globe and layers (including layer contents) are reflected on screen sometime after
     * calling this method. May be called from any thread.
     */
    override fun requestRedraw() {
        if (!::engine.isInitialized) return
        if (!isWaitingForRedraw && !engine.viewport.isEmpty) {
            isWaitingForRedraw = true
            try {
                renderFrame(Frame.obtain(framePool))
            } catch (e: Exception) {
                logMessage(ERROR, "WorldWindow", "requestRedraw", "Exception while rendering frame", e)
                isWaitingForRedraw = false
            }
        }
    }

    /**
     * Removes a resource ID from the missed resource list.
     */
    override fun unmarkResourceAbsent(resourceId: Int) {
        engine.renderResourceCache.absentResourceList.unmarkResourceAbsent(resourceId)
    }

    override fun init(drawable: GLAutoDrawable) {
        if (!::engine.isInitialized) {
            val gl = drawable.gl.gL3ES3
            engine = WorldWind(JoglKgl(gl), renderResourceCache)
            WorldWind.addListener(this)
        }
        engine.setupDrawContext()

        WorldWindowLayerFactoryScope(this).layerFactory()
    }

    override fun dispose(drawable: GLAutoDrawable) {
        clearFrameQueue()
        eventsJob?.cancel()
        eventsJob = null
        if (::engine.isInitialized) {
            engine.reset()
            engine.renderResourceCache.clear()
        }
    }

    override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
        if (!::engine.isInitialized || width <= 0 || height <= 0) return
        engine.setupViewport(width, height, 1f)
        requestRedraw()
    }

    override fun display(drawable: GLAutoDrawable) {
        if (!::engine.isInitialized) return

        pickQueue.poll()?.let { pickFrame ->
            try {
                synchronized(engine.renderResourceCache) { engine.drawFrame(pickFrame) }
            } catch (e: Exception) {
                logMessage(ERROR, "WorldWindow", "display", "Exception while processing pick in OpenGL thread", e)
            } finally {
                pickFrame.recycle()
            }
        }

        frameQueue.poll()?.let { nextFrame ->
            currentFrame?.recycle()
            currentFrame = nextFrame
            isWaitingForRedraw = false
        }

        try {
            currentFrame?.let { synchronized(engine.renderResourceCache) { engine.drawFrame(it) } }
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "display", "Exception while drawing frame in OpenGL thread", e)
        }
    }

    protected open fun renderFrame(frame: Frame) {
        val redrawRequired = synchronized(engine.renderResourceCache) { engine.renderFrame(frame) }
        if (redrawRequired) isWaitingForRedraw = false

        if (frame.isPickMode) pickQueue.offer(frame) else frameQueue.offer(frame)
        scheduleDisplay()

        if (!frame.isPickMode && redrawRequired) requestRedraw()
    }

    protected open fun clearFrameQueue() {
        while (true) pickQueue.poll()?.recycle() ?: break
        while (true) frameQueue.poll()?.recycle() ?: break
        currentFrame?.recycle()
        currentFrame = null
        isWaitingForRedraw = false
    }

    protected open fun scheduleDisplay() {
        if (SwingUtilities.isEventDispatchThread()) glPanel.display() else SwingUtilities.invokeLater { glPanel.display() }
    }

    protected open fun dispatchMouseEvent(event: MouseEvent): Boolean {
        if (!::engine.isInitialized) return false
        return try {
            when {
                selectDragDetector.onMouseEvent(event) -> true
                controller.onMouseEvent(event) -> true
                else -> false
            }
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "dispatchMouseEvent", "Exception while handling mouse event '$event'", e)
            false
        }
    }

    protected open fun dispatchWheelEvent(event: MouseWheelEvent): Boolean {
        if (!::engine.isInitialized) return false
        return try {
            controller.onMouseWheelEvent(event)
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "dispatchWheelEvent", "Exception while handling wheel event '$event'", e)
            false
        }
    }

    companion object {
        @JvmStatic
        protected fun defaultCapabilities(): GLCapabilities {
            val profile = GLProfile.get(GLProfile.GL4ES3)
            return GLCapabilities(profile).apply {
                sampleBuffers = false
            }
        }
    }
}

/**
 * Scope receiver used by [WorldWindow.layerFactory] to add layers to a [WorldWindow].
 */
class WorldWindowLayerFactoryScope(val worldWindow: WorldWindow) {
    /**
     * Adds a layer using `+layer` DSL syntax.
     */
    operator fun plus(layer: Layer) {
        add(layer)
    }

    /**
     * Adds a [Layer] to the associated [WorldWindow].
     */
    fun add(layer: Layer) {
        worldWindow.engine.layers.addLayer(layer)
    }
}