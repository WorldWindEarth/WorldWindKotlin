package earth.worldwind

import earth.worldwind.frame.Frame
import earth.worldwind.geom.Line
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Viewport
import earth.worldwind.gesture.SelectDragListener
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.WebKgl
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.khronos.webgl.WebGLContextAttributes
import org.khronos.webgl.WebGLContextEvent
import org.khronos.webgl.WebGLRenderingContext
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Represents a WorldWind window for an HTML canvas.
 */
open class WorldWindow(
    /**
     * The HTML canvas associated with this WorldWindow.
     */
    val canvas: HTMLCanvasElement,
    /**
     * Render resource cache capacity in bytes
     */
    cacheCapacity: Long = RenderResourceCache.recommendedCapacity()
) {
    /**
     * WebGL context associated with the HTML canvas.
     */
    protected val gl = createContext(canvas)
    /**
     * Main WorldWindow scope to execute jobs which should be cancelled on GL context lost
     */
    val mainScope = MainScope()
    /**
     * Main WorldWind engine, containing globe, terrain, renderable layers, camera, viewport and frame rendering logic.
     */
    open val engine = WorldWind(WebKgl(gl), RenderResourceCache(mainScope, cacheCapacity))
    /**
     * List of registered event listeners for the specified event type on this WorldWindow's canvas.
     */
    protected val eventListeners = mutableMapOf<String, EventListenerEntry>()
    /**
     * The controller used to manipulate the globe.
     */
    var controller: WorldWindowController = BasicWorldWindowController(this)
    /**
     * The controller used to manipulate the globe with the keyboard.
     */
    var keyboardControls = KeyboardControls(this)
    /**
     * Helper class to process Renderable selection and drag. Assign [SelectDragListener.callback] to handle events.
     */
    var selectDragListener = SelectDragListener(this)
    /**
     * The list of callbacks to call immediately before and immediately after performing a redrawn. The callbacks
     * have two arguments: this WorldWindow and the redraw stage, e.g., <code style='white-space:nowrap'>redrawCallback(worldWindow, stage);</code>.
     * The stage will be either WorldWind.BEFORE_REDRAW or WorldWind.AFTER_REDRAW indicating whether the
     * callback has been called either immediately before or immediately after a redrawn, respectively.
     * Applications may add functions to this array or remove them.
     */
    val redrawCallbacks = mutableSetOf<(WorldWindow, RedrawStage)->Unit>()
    protected val frame = Frame()
    protected var redrawRequestId = 0
    protected var isRedrawRequested = false

    protected class EventListenerEntry(val callback: (Event) -> Unit) {
        val listeners = mutableListOf<EventListener>()
    }

    init {
        // Prevent the browser's default actions in response to mouse and touch events, which interfere with
        // navigation. Register these event listeners  before any others to ensure that they're called last.
        val preventDefaultListener = EventListener { e -> e.preventDefault() }
        addEventListener("mousedown", preventDefaultListener)
        addEventListener("touchstart", preventDefaultListener)
        addEventListener("contextmenu", preventDefaultListener)
        addEventListener("wheel", preventDefaultListener)

        // Redirect various UI interactions to the appropriate handler.
        val onGestureEvent = EventListener { e -> controller.handleEvent(e) }
        if (window.navigator.maxTouchPoints == 0) {
            // Prevent the browser's default actions in response to pointer events which interfere with navigation.
            // This CSS style property is configured here to ensure that it's set for all applications.
            canvas.style.setProperty("touch-action", "none")

            addEventListener("pointerdown", onGestureEvent)
            window.addEventListener("pointermove", onGestureEvent, false) // get pointermove events outside event target
            window.addEventListener("pointercancel", onGestureEvent, false) // get pointercancel events outside event target
            window.addEventListener("pointerup", onGestureEvent, false) // get pointerup events outside event target
        } else {
            addEventListener("mousedown", onGestureEvent)
            window.addEventListener("mousemove", onGestureEvent, false) // get mousemove events outside event target
            window.addEventListener("mouseup", onGestureEvent, false) // get mouseup events outside event target
            addEventListener("touchstart", onGestureEvent)
            addEventListener("touchmove", onGestureEvent)
            addEventListener("touchend", onGestureEvent)
            addEventListener("touchcancel", onGestureEvent)
        }
        addEventListener("wheel", onGestureEvent)

        // Set up to handle WebGL context events.
        canvas.addEventListener("webglcontextlost",
            { event -> event as WebGLContextEvent
                log(INFO, "WebGL context event: " + event.statusMessage)
                // Inform WebGL that we handle context restoration, enabling the context restored event to be delivered.
                event.preventDefault()
                // Notify the draw context that the WebGL rendering context has been lost.
                contextLost()
            }, false)
        canvas.addEventListener("webglcontextrestored",
            { event -> event as WebGLContextEvent
                log(INFO, "WebGL context event: " + event.statusMessage)
                // Notify the draw context that the WebGL rendering context has been restored.
                contextRestored()
            }, false)

        // Set up WebGL context and start rendering to the WebGL context in an animation frame loop.
        this.contextRestored()
    }

    /**
     * Registers an event listener for the specified event type on this WorldWindow's canvas. This function
     * delegates the processing of events to the WorldWindow's canvas. For details on this function and its
     * arguments, see the W3C [org.w3c.dom.events.EventTarget] documentation.
     * @see <a href="https://www.w3.org/TR/DOM-Level-2-Events/events.html#Events-EventTarget">EventTarget</a>
     *
     * When an event occurs, this calls the registered event listeners in order of reverse registration.
     *
     * @param type The event type to listen for.
     * @param listener The [EventListener] to call when the event occurs.
     */
    fun addEventListener(type: String, listener: EventListener) {
        var entry = eventListeners[type]
        if (entry == null) {
            entry = EventListenerEntry { event ->
                event.asDynamic().worldWindow = this@WorldWindow
                // calls listeners in reverse registration order
                entry?.listeners?.forEach{ l -> l.handleEvent(event) }
            }.also { eventListeners[type] = it }
        }

        if (!entry.listeners.contains(listener)) { // suppress duplicate listeners
            entry.listeners.add(0, listener) // insert the listener at the beginning of the list
            // first listener added, add the event listener callback
            if (entry.listeners.size == 1) canvas.addEventListener(type, entry.callback, false)
        }
    }

    /**
     * Removes an event listener for the specified event type from this WorldWindow's canvas. The listener must be
     * the same object passed to addEventListener. Calling removeEventListener with arguments that do not identify a
     * currently registered listener has no effect.
     *
     * @param type Indicates the event type the listener registered for.
     * @param listener The listener to remove.
     */
    fun removeEventListener(type: String, listener: EventListener) {
        val entry = eventListeners[type] ?: return // no entry for the specified type
        if (entry.listeners.remove(listener) && entry.listeners.isEmpty()) {
            canvas.removeEventListener(type, entry.callback, false)
        }
    }

    /**
     * Causes this WorldWindow to redraw itself at the next available opportunity. The redrawn occurs on the main
     * thread at a time of the browser's discretion. Applications should call redraw after changing the World
     * Window's state, but should not expect that change to be reflected on screen immediately after this function
     * returns. This is the preferred method for requesting a redrawn of the WorldWindow.
     */
    fun requestRedraw() { isRedrawRequested = true } // redraw during the next animation frame

    /**
     * Converts window coordinates to coordinates relative to this WorldWindow's canvas.
     * @param x The X coordinate to convert.
     * @param y The Y coordinate to convert.
     * @returns The converted coordinates.
     */
    fun canvasCoordinates(x: Number, y: Number): Vec2 {
        val bbox = canvas.getBoundingClientRect()
        val xc = x.toDouble() - (bbox.left + canvas.clientLeft) // * canvas.width / bbox.width
        val yc = y.toDouble() - (bbox.top + canvas.clientTop) // * canvas.height / bbox.height
        return Vec2(xc, yc)
    }

    /**
     * Requests the WorldWind objects displayed at a specified screen-coordinate point.
     *
     * If the point intersects the terrain, the returned list contains an object identifying the associated geographic
     * position. This returns an empty list when nothing in the WorldWind scene intersects the specified point.
     *
     * @param pickPoint The point to examine in this WorldWindow's screen coordinates.
     * @returns A list of picked WorldWind objects at the specified pick point.
     */
    fun pick(pickPoint: Vec2) = pickShapesInRegion(pickPoint.x, pickPoint.y)

    /**
     * Requests the WorldWind objects displayed within a specified screen-coordinate region. This returns all
     * objects that intersect the specified region, regardless of whether an object is actually visible, and
     * marks objects that are visible as on top.
     *
     * @param x      the X coordinate relative to this WorldWindow's canvas.
     * @param y      the Y coordinate relative to this WorldWindow's canvas.
     * @param width  the width in canvas pixels
     * @param height the height in canvas pixels
     * @param pickCenter picks top shape and terrain in rectangle center as priority
     *
     * @returns A list of visible WorldWind objects within the specified region.
     */
    fun pickShapesInRegion(
        x: Double, y: Double, width: Double = 0.0, height: Double = 0.0, pickCenter: Boolean = true
    ): PickedObjectList {
        // Allocate a list in which to collect and return the picked objects.
        val pickedObjects = PickedObjectList()

        // Nothing can be picked if viewport is undefined.
        val viewport = engine.viewport
        if (viewport.isEmpty) return pickedObjects

        // Determine pick viewport
        val pickViewport = if (width != 0.0 && height != 0.0) Viewport(
            floor(x).toInt(), viewport.height - ceil(y + height).toInt(), ceil(width).toInt(), ceil(height).toInt()
        ) else Viewport(x.roundToInt() - 1, viewport.height - y.roundToInt() - 1, 3, 3)
        if (!pickViewport.intersect(viewport)) return pickedObjects

        // Prepare pick frame
        frame.pickedObjects = pickedObjects
        frame.pickViewport = pickViewport
        if (pickCenter) {
            // Compute the pick point in OpenGL screen coordinates, rounding to the nearest whole pixel. Nothing can be picked
            // if pick point is outside the WorldWindow's viewport.
            val px = pickViewport.x + pickViewport.width / 2.0
            val py = pickViewport.y + pickViewport.height / 2.0
            if(viewport.contains(px.roundToInt(), py.roundToInt())) {
                val pickRay = Line()
                if (engine.rayThroughScreenPoint(px, py, pickRay)) {
                    frame.pickPoint = Vec2(px, py)
                    frame.pickRay = pickRay
                }
            }
        }
        frame.isPickMode = true
        redrawFrame()

        return pickedObjects
    }

    /**
     * Notifies this draw context that the current WebGL rendering context has been lost. This function removes all
     * cached WebGL resources and resets all properties tracking the current WebGL state.
     */
    protected open fun contextLost() {
        // Stop the rendering animation frame loop, resuming only if the WebGL context is restored.
        window.cancelAnimationFrame(redrawRequestId)

        // Cancel all async jobs but keep scope reusable
        mainScope.coroutineContext.cancelChildren()

        // Remove all cached WebGL resources, which are now invalid.
        engine.reset()
    }

    /**
     * Notifies this draw context that the current WebGL rendering context has been restored. This function prepares
     * this draw context to resume rendering.
     */
    protected open fun contextRestored() {
        // Remove all cached WebGL resources. This cache is already cleared when the context is lost, but
        // asynchronous load operations that complete between context lost and context restored populate the cache
        // with invalid entries.
        engine.reset()

        // Specify the default WorldWind OpenGL state.
        engine.setupDrawContext()

        // Store current screen density factor
        engine.densityFactor = window.devicePixelRatio.toFloat()

        // Enable WebGL depth texture extension to be able to use GL_DEPTH_COMPONENT texture format
        gl.getExtension("WEBGL_depth_texture")

        // Subscribe on redraw events from WorldWind's global event bus.
        mainScope.launch {
            WorldWind.eventBus.filterIsInstance<WorldWind.RequestRedrawEvent>().collectLatest { requestRedraw() }
        }

        // Request redraw at least once.
        requestRedraw()

        // Resume the rendering animation frame loop until the WebGL context is lost.
        animationFrameLoop()
    }

    protected open fun animationFrameLoop() {
        // Render to the WebGL context as needed.
        redrawIfNeeded()

        // Continue the animation frame loop until the WebGL context is lost.
        redrawRequestId = window.requestAnimationFrame { animationFrameLoop() }
    }

    protected open fun redrawIfNeeded() {
        // Check if the drawing buffer needs to resize to match its screen size, which requires a redrawn.
        resize()

        // Redraw the WebGL drawing buffer only when necessary.
        if (!isRedrawRequested) return
        isRedrawRequested = false
        redrawFrame()
    }

    protected open fun redrawFrame() {
        val isPickMode = frame.isPickMode
        try {
            // Prepare to redraw and notify redraw callbacks that a redrawn is about to occur.
            if(!isPickMode) callRedrawCallbacks(RedrawStage.BEFORE_REDRAW)
            // Render frame. Propagate redraw requests submitted during rendering.
            if (engine.renderFrame(frame) || isPickMode) requestRedraw()
            // Redraw the WebGL drawing buffer.
            engine.drawFrame(frame)
        } catch (e: Exception) {
            logMessage(
                ERROR, "WorldWindow", "drawFrame", "Exception occurred during redrawing.\n$e"
            )
        } finally {
            // Recycle each frame to be reused
            frame.recycle()
            // Notify redraw callbacks that a redrawn has completed.
            if(!isPickMode) callRedrawCallbacks(RedrawStage.AFTER_REDRAW)
        }
    }

    protected open fun resize() {
        // Check if canvas size is changed
        val width = (gl.canvas.clientWidth * engine.densityFactor).roundToInt()
        val height = (gl.canvas.clientHeight * engine.densityFactor).roundToInt()

        if (gl.canvas.width != width || gl.canvas.height != height || engine.viewport.isEmpty) {
            // Make the canvas drawing buffer size match its screen size.
            gl.canvas.width = width
            gl.canvas.height = height

            // Set the WebGL viewport to match the canvas drawing buffer size.
            engine.setupViewport(gl.drawingBufferWidth, gl.drawingBufferHeight)

            // Cause this WorldWindow to redraw with the new size.
            requestRedraw()
        }
    }

    protected open fun callRedrawCallbacks(stage: RedrawStage) = redrawCallbacks.forEach {
        try {
            it(this, stage)
        } catch (e: Exception) {
            // Keep going. Execute the rest of the callbacks.
            log(ERROR, "Exception calling redraw callback.\n$e")
        }
    }

    companion object {
        /**
         * Create the WebGL context associated with the HTML canvas.
         */
        protected fun createContext(canvas: HTMLCanvasElement): WebGLRenderingContext {
            // Request a WebGL context with antialiasing is disabled. Antialiasing causes gaps to appear at the edges of
            // terrain tiles.
            val glAttrs = WebGLContextAttributes(antialias = false)
            val context = canvas.getContext("webgl", glAttrs)
                ?: canvas.getContext("experimental-webgl", glAttrs)
            require(context is WebGLRenderingContext) {
                logMessage(ERROR, "WorldWindow", "createContext", "webglNotSupported")
            }
            return context
        }
    }

    enum class RedrawStage {
        /**
         * Indicates that a redrawn callback has been called immediately after a redrawn.
         */
        AFTER_REDRAW,
        /**
         * Indicates that a redrawn callback has been called immediately before a redrawn.
         */
        BEFORE_REDRAW;
    }
}