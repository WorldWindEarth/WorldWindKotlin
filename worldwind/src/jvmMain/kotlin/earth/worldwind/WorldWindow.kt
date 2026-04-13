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
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.BasicPool
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.GL_POINT_SPRITE
import earth.worldwind.util.kgl.GL_PROGRAM_POINT_SIZE
import earth.worldwind.util.kgl.JoglKgl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.util.LinkedList
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
 * @param factory DSL block invoked during initialization to populate [WorldWind.layers]
 */
open class WorldWindow @JvmOverloads constructor(
    protected val renderResourceCache: RenderResourceCache = RenderResourceCache(),
    capabilities: GLCapabilities = defaultCapabilities(),
    protected val factory: (WorldWind) -> Unit
) : JPanel(BorderLayout()), WorldWind.EventListener {
    /**
     * Main WorldWind engine, containing globe, terrain, renderable layers, camera, viewport and frame rendering logic.
     */
    lateinit var engine: WorldWind
        protected set

    /**
     * Current WorldWindow camera and gestures controller.
     */
    var controller: WorldWindowController = BasicWorldWindowController(this)

    /**
     * Renderable selection and drag gestures detector. Assign [SelectDragDetector.callback] to handle events.
     */
    open val selectDragDetector = SelectDragDetector(this)

    protected val framePool = BasicPool<Frame>()
    protected val frameQueue = LinkedList<Frame>()
    protected val pickQueue = LinkedList<Frame>()
    protected var currentFrame: Frame? = null
    @Volatile
    protected var isWaitingForRedraw = false

    /**
     * Swing OpenGL panel that presents rendered frames.
     * WorldWindow does not inherit [GLJPanel] directly to hide JOGL dependency from application.
     */
    private val glPanel = GLJPanel(capabilities)

    /**
     * Keep JOGL-specific listener private to avoid leaking JOGL types in WorldWindow's public API.
     **/
    private val glEventListener = object : GLEventListener {
        override fun init(drawable: GLAutoDrawable) {
            val gl = drawable.gl.gL3ES3
            if (!::engine.isInitialized) engine = WorldWind(JoglKgl(gl), renderResourceCache)
            WorldWind.addListener(this@WorldWindow)
            engine.setupDrawContext()

            // Enable desktop OpenGL features required for point sprite rendering
            gl.glEnable(GL_PROGRAM_POINT_SIZE) // Allows gl_PointSize in vertex shader
            gl.glEnable(GL_POINT_SPRITE) // Populates gl_PointCoord in fragment shader

            // Apply external initialization
            factory(engine)
        }

        override fun dispose(drawable: GLAutoDrawable) {
            clearFrameQueue()
            WorldWind.removeListener(this@WorldWindow)
            if (::engine.isInitialized) {
                engine.renderResourceCache.clear()
                engine.reset()
            }
        }

        override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
            if (!::engine.isInitialized || width <= 0 || height <= 0) return
            engine.setupViewport(width, height, Toolkit.getDefaultToolkit().screenResolution / 96f)
            doRequestRedraw()
        }

        override fun display(drawable: GLAutoDrawable) {
            if (!::engine.isInitialized) return

            pickQueue.poll()?.let { pickFrame ->
                try {
                    engine.drawFrame(pickFrame)
                } catch (e: Exception) {
                    logMessage(ERROR, "WorldWindow", "display", "Exception while processing pick", e)
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
                currentFrame?.let { engine.drawFrame(it) }
            } catch (e: Exception) {
                logMessage(ERROR, "WorldWindow", "display", "Exception while drawing frame", e)
            }
        }
    }

    init {
        add(glPanel, BorderLayout.CENTER)
        glPanel.addGLEventListener(glEventListener)
        glPanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = dispatchMouseEvent(e)
            override fun mouseReleased(e: MouseEvent) = dispatchMouseEvent(e)
            override fun mouseClicked(e: MouseEvent) = dispatchMouseEvent(e)
            override fun mouseExited(e: MouseEvent) = dispatchMouseEvent(e)
        })
        glPanel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) = dispatchMouseEvent(e)
            override fun mouseMoved(e: MouseEvent) = dispatchMouseEvent(e)
        })
        glPanel.addMouseWheelListener(::dispatchWheelEvent)
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
     * Removes a resource ID from the missed resource list.
     */
    override fun unmarkResourceAbsent(resourceId: Int) {
        SwingUtilities.invokeLater { engine.renderResourceCache.absentResourceList.unmarkResourceAbsent(resourceId) }
    }

    /**
     * Request that this WorldWindow update its display. Prior changes to this WorldWindow's
     * camera, globe and layers (including layer contents) are reflected on screen sometime after
     * calling this method. May be called from any thread.
     */
    override fun requestRedraw() {
        if (SwingUtilities.isEventDispatchThread()) doRequestRedraw()
        else SwingUtilities.invokeLater { doRequestRedraw() }
    }

    protected open fun doRequestRedraw() {
        if (!isWaitingForRedraw && ::engine.isInitialized && !engine.viewport.isEmpty) {
            isWaitingForRedraw = true
            SwingUtilities.invokeLater {
                try {
                    renderFrame(Frame.obtain(framePool))
                } catch (e: Exception) {
                    logMessage(ERROR, "WorldWindow", "requestRedraw", "Exception while rendering frame", e)
                    isWaitingForRedraw = false
                }
            }
        }
    }

    protected open fun renderFrame(frame: Frame) {
        val redrawRequired = engine.renderFrame(frame)
        if (redrawRequired) isWaitingForRedraw = false
        if (frame.isPickMode) pickQueue.offer(frame) else frameQueue.offer(frame)
        glPanel.display()
        if (!frame.isPickMode && redrawRequired) doRequestRedraw()
    }

    protected open fun clearFrameQueue() {
        while (true) pickQueue.poll()?.recycle() ?: break
        while (true) frameQueue.poll()?.recycle() ?: break
        currentFrame?.recycle()
        currentFrame = null
        isWaitingForRedraw = false
    }

    protected open fun dispatchMouseEvent(event: MouseEvent) {
        if (!::engine.isInitialized) return
        try {
            selectDragDetector.onMouseEvent(event) || controller.onMouseEvent(event)
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "dispatchMouseEvent", "Exception while handling mouse event '$event'", e)
        }
    }

    protected open fun dispatchWheelEvent(event: MouseWheelEvent) {
        if (!::engine.isInitialized) return
        try {
            controller.onMouseWheelEvent(event)
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "dispatchWheelEvent", "Exception while handling wheel event '$event'", e)
        }
    }

    companion object {
        @JvmStatic
        protected fun defaultCapabilities() = GLCapabilities(GLProfile.get(GLProfile.GL4ES3))
    }
}