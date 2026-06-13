@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)

package earth.worldwind

import earth.worldwind.frame.Frame
import earth.worldwind.geom.Line
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Viewport
import earth.worldwind.gesture.TouchEvent
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_CANCEL
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_DOWN
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_MOVE
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_POINTER_DOWN
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_POINTER_UP
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_UP
import earth.worldwind.navigator.NavigatorEventSupport
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.SynchronizedPool
import earth.worldwind.util.kgl.IosKgl
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import platform.CoreGraphics.CGRect
import platform.EAGL.EAGLContext
import platform.EAGL.EAGLDrawableProtocol
import platform.EAGL.kEAGLColorFormatRGBA8
import platform.EAGL.kEAGLDrawablePropertyColorFormat
import platform.EAGL.kEAGLDrawablePropertyRetainedBacking
import platform.EAGL.kEAGLRenderingAPIOpenGLES3
import platform.EAGL.presentRenderbuffer
import platform.EAGL.renderbufferStorage
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithBool
import platform.QuartzCore.CADisplayLink
import platform.QuartzCore.CAEAGLLayer
import platform.UIKit.UIEvent
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UIGestureRecognizerState
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateCancelled
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIPanGestureRecognizer
import platform.UIKit.UIPinchGestureRecognizer
import platform.UIKit.UIRotationGestureRecognizer
import platform.UIKit.UIScreen
import platform.UIKit.UITouch
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta
import platform.darwin.NSObject
import earth.worldwind.gesture.GestureState
import earth.worldwind.gesture.SelectDragDetector
import platform.gles3.GL_COLOR_ATTACHMENT0
import platform.gles3.GL_DEPTH_STENCIL_ATTACHMENT
import platform.gles3.GL_DEPTH24_STENCIL8
import platform.gles3.GL_FRAMEBUFFER
import platform.gles3.GL_RENDERBUFFER
import platform.gles3.GL_RENDERBUFFER_HEIGHT
import platform.gles3.GL_RENDERBUFFER_WIDTH
import platform.gles3.glBindFramebuffer
import platform.gles3.glBindRenderbuffer
import platform.gles3.glDeleteFramebuffers
import platform.gles3.glDeleteRenderbuffers
import platform.gles3.glFramebufferRenderbuffer
import platform.gles3.glGenFramebuffers
import platform.gles3.glGenRenderbuffers
import platform.gles3.glGetRenderbufferParameteriv
import platform.gles3.glRenderbufferStorage
import platform.gles3.glViewport
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Lets pinch+rotation+pan recognizers fire together. K/N NSObject subclasses must be regular classes. */
@ExportObjCClass
private class SimultaneousGestureDelegate : NSObject(), UIGestureRecognizerDelegateProtocol {
    override fun gestureRecognizer(
        gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer
    ): Boolean = true
}

/** iOS host for the WorldWind engine. UIView with a CAEAGLLayer-backed framebuffer and a
 *  CADisplayLink-driven render loop. */
@ExportObjCClass
class WorldWindow @OverrideInit constructor(frame: CValue<CGRect>) : UIView(frame) {

    companion object : UIViewMeta() {
        override fun layerClass(): ObjCClass = CAEAGLLayer
    }

    private val iosKgl = IosKgl()

    val engine = WorldWind(iosKgl, RenderResourceCache())

    private var eaglContext: EAGLContext? = null
    private var framebuffer: UInt = 0u
    private var colorRenderbuffer: UInt = 0u
    private var depthRenderbuffer: UInt = 0u
    private var fbWidth: Int = 0
    private var fbHeight: Int = 0

    /** K/N forbids mixing Kotlin interfaces with ObjC superclasses, so we forward instead of
     *  implementing [WorldWind.EventListener] directly. Exposed for `:worldwind-compose`. */
    val eventListener: WorldWind.EventListener = object : WorldWind.EventListener {
        override fun requestRedraw() = this@WorldWindow.requestRedraw()
        override fun unmarkResourceAbsent(resourceId: Int) = this@WorldWindow.unmarkResourceAbsent(resourceId)
    }

    var controller: WorldWindowController = BasicWorldWindowController(this)

    val selectDragDetector = SelectDragDetector(this)

    val navigatorEvents = NavigatorEventSupport(this)

    protected val framePool = SynchronizedPool<Frame>()
    protected val frameQueue = ArrayDeque<Frame>()
    protected val pickQueue = ArrayDeque<Frame>()
    protected var currentFrame: Frame? = null
    protected var isWaitingForRedraw = false

    /** Stable integer pointer id per UITouch, recycled when fingers lift (Android-style). */
    private val touchToPointerId = mutableMapOf<UITouch, Int>()
    private var nextPointerId = 0
    private val touchEvent = TouchEvent()

    private var displayLink: CADisplayLink? = null

    /** [UIGestureRecognizer.delegate] is a weak ref - hold the delegate strongly here. */
    private val simultaneousGestureDelegate = SimultaneousGestureDelegate()

    init {
        // Pre-multiply touch coords by screen scale so gesture deltas == GL viewport pixels.
        contentScaleFactor = UIScreen.mainScreen.scale
        multipleTouchEnabled = true

        @Suppress("UNCHECKED_CAST")
        val eaglLayer = layer as CAEAGLLayer
        eaglLayer.opaque = true
        eaglLayer.drawableProperties = mapOf<Any?, Any>(
            kEAGLDrawablePropertyRetainedBacking to NSNumber.numberWithBool(false),
            kEAGLDrawablePropertyColorFormat to kEAGLColorFormatRGBA8,
        )

        val ctx = EAGLContext(aPI = kEAGLRenderingAPIOpenGLES3)
        EAGLContext.setCurrentContext(ctx)
        eaglContext = ctx

        engine.setupDrawContext()

        val link = CADisplayLink.displayLinkWithTarget(
            target = this,
            selector = NSSelectorFromString("doFrame:")
        )
        link.paused = true
        link.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        displayLink = link

        WorldWind.addListener(eventListener)

        // Native UIKit recognizers for indirect input (Mac trackpad gestures under "Designed
        // for iPad"). The UITouch-based recognizers in BasicWorldWindowController only see
        // real touches; trackpad pinch / rotate / two-finger drag arrive via UIKit's native
        // recognizers and bridge into the controller's `handleIndirect*` entry points.
        val d = simultaneousGestureDelegate
        addGestureRecognizer(UIPinchGestureRecognizer(
            target = this, action = NSSelectorFromString("handleNativePinch:")
        ).apply { delegate = d })
        addGestureRecognizer(UIRotationGestureRecognizer(
            target = this, action = NSSelectorFromString("handleNativeRotate:")
        ).apply { delegate = d })
        addGestureRecognizer(UIPanGestureRecognizer(
            target = this, action = NSSelectorFromString("handleNativeTilt:")
        ).apply {
            // allowedScrollTypesMask = 3 (continuous | discrete) is what makes trackpad
            // pans drive the recognizer despite there being no real touches.
            minimumNumberOfTouches = 2.toULong()
            maximumNumberOfTouches = 2.toULong()
            allowedScrollTypesMask = 3L
            delegate = d
        })
    }

    /** Mac trackpad pinch -> camera dolly around the cursor (no UITouch events on indirect input). */
    @ObjCAction
    fun handleNativePinch(sender: UIPinchGestureRecognizer) {
        val ctrl = controller as? BasicWorldWindowController ?: return
        val state = nativeGestureState(sender.state) ?: return
        val scale = sender.scale.toFloat()
        val s = contentScaleFactor.toFloat()
        sender.locationInView(this).useContents {
            ctrl.handleIndirectPinch(state, scale, (x * s).toFloat(), (y * s).toFloat())
        }
    }

    /** Mac trackpad two-finger rotation -> camera heading.
     *  locationInView is the gesture centroid; we forward it so the controller can anchor the
     *  rotation around that point (matches iOS two-finger rotation focal-point model). */
    @ObjCAction
    fun handleNativeRotate(sender: UIRotationGestureRecognizer) {
        val ctrl = controller as? BasicWorldWindowController ?: return
        val state = nativeGestureState(sender.state) ?: return
        val s = contentScaleFactor.toFloat()
        sender.locationInView(this).useContents {
            ctrl.handleIndirectRotate(state, sender.rotation.toFloat(), (x * s).toFloat(), (y * s).toFloat())
        }
    }

    /** Mac trackpad two-finger pan -> camera tilt. */
    @ObjCAction
    fun handleNativeTilt(sender: UIPanGestureRecognizer) {
        val ctrl = controller as? BasicWorldWindowController ?: return
        val state = nativeGestureState(sender.state) ?: return
        sender.translationInView(this).useContents {
            ctrl.handleIndirectTilt(state, y.toFloat())
        }
    }

    private fun nativeGestureState(uiState: UIGestureRecognizerState): GestureState? = when (uiState) {
        UIGestureRecognizerStateBegan -> GestureState.BEGAN
        UIGestureRecognizerStateChanged -> GestureState.CHANGED
        UIGestureRecognizerStateEnded -> GestureState.ENDED
        UIGestureRecognizerStateCancelled -> GestureState.CANCELLED
        else -> null
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val ctx = eaglContext ?: return
        val newW = (bounds.useContents { size.width } * contentScaleFactor).toInt()
        val newH = (bounds.useContents { size.height } * contentScaleFactor).toInt()
        if (newW <= 0 || newH <= 0) return
        if (newW == fbWidth && newH == fbHeight && framebuffer != 0u) return

        EAGLContext.setCurrentContext(ctx)
        recreateFramebuffer(ctx)
        // Publish the new framebuffer id to IosKgl so engine "bind FB 0" remaps to us.
        iosKgl.defaultFramebuffer = framebuffer
        engine.setupViewport(fbWidth, fbHeight, contentScaleFactor.toFloat())
        requestRedraw()
    }

    /** Color renderbuffer is backed by the CAEAGLLayer's drawable; depth+stencil is a
     *  packed GL_DEPTH24_STENCIL8 sized to match. Stencil bits are required by the 3D
     *  Tiles skip-LoD mask. */
    private fun recreateFramebuffer(ctx: EAGLContext) {
        deleteRenderbuffer(colorRenderbuffer); colorRenderbuffer = 0u
        deleteRenderbuffer(depthRenderbuffer); depthRenderbuffer = 0u
        deleteFramebufferId(framebuffer); framebuffer = 0u

        memScoped {
            val v = alloc<UIntVar>()
            glGenFramebuffers(1, v.ptr); framebuffer = v.value
            glGenRenderbuffers(1, v.ptr); colorRenderbuffer = v.value
            glGenRenderbuffers(1, v.ptr); depthRenderbuffer = v.value
        }

        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), framebuffer)
        glBindRenderbuffer(GL_RENDERBUFFER.toUInt(), colorRenderbuffer)
        @Suppress("UNCHECKED_CAST")
        val drawable = layer as EAGLDrawableProtocol
        ctx.renderbufferStorage(GL_RENDERBUFFER.toULong(), fromDrawable = drawable)

        // Read back the GL-allocated pixel dimensions; can differ from bounds*scale when laid
        // out at sub-pixel positions.
        memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            glGetRenderbufferParameteriv(GL_RENDERBUFFER.toUInt(), GL_RENDERBUFFER_WIDTH.toUInt(), w.ptr)
            glGetRenderbufferParameteriv(GL_RENDERBUFFER.toUInt(), GL_RENDERBUFFER_HEIGHT.toUInt(), h.ptr)
            fbWidth = w.value
            fbHeight = h.value
        }

        glFramebufferRenderbuffer(
            GL_FRAMEBUFFER.toUInt(), GL_COLOR_ATTACHMENT0.toUInt(),
            GL_RENDERBUFFER.toUInt(), colorRenderbuffer
        )

        glBindRenderbuffer(GL_RENDERBUFFER.toUInt(), depthRenderbuffer)
        glRenderbufferStorage(GL_RENDERBUFFER.toUInt(), GL_DEPTH24_STENCIL8.toUInt(), fbWidth, fbHeight)
        glFramebufferRenderbuffer(
            GL_FRAMEBUFFER.toUInt(), GL_DEPTH_STENCIL_ATTACHMENT.toUInt(),
            GL_RENDERBUFFER.toUInt(), depthRenderbuffer
        )
    }

    private fun deleteFramebufferId(id: UInt) {
        if (id == 0u) return
        memScoped {
            val v = alloc<UIntVar>().apply { value = id }
            glDeleteFramebuffers(1, v.ptr)
        }
    }

    private fun deleteRenderbuffer(id: UInt) {
        if (id == 0u) return
        memScoped {
            val v = alloc<UIntVar>().apply { value = id }
            glDeleteRenderbuffers(1, v.ptr)
        }
    }

    /** Reset to initial state — called when the view is removed from its window or destroyed.
     *  CADisplayLink retains its target (`this`); without invalidate the view never deallocates. */
    private fun reset() {
        controller.release()
        navigatorEvents.reset()
        engine.reset()
        clearFrameQueue()
        displayLink?.invalidate()
        displayLink = null
        isWaitingForRedraw = false
        WorldWind.removeListener(eventListener)
    }

    override fun willMoveToSuperview(newSuperview: UIView?) {
        super.willMoveToSuperview(newSuperview)
        if (newSuperview == null) reset()
    }

    fun pickAsync(x: Float, y: Float, width: Float = 0f, height: Float = 0f, pickCenter: Boolean = true): Deferred<PickedObjectList> {
        val pickedObjects = PickedObjectList()
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
     * Synchronous variant of [pickAsync]. iOS has no separate GL thread, so we drain the pick
     * frame inline on the main thread - the same model JVM's `SelectDragDetector` relies on
     * (it wraps `pickAsync` in `runBlocking`). Without this, async pick-during-DOWN leaves a
     * window where the navigation controller can start panning before we know whether the
     * gesture should drag a renderable.
     */
    fun pick(x: Float, y: Float, width: Float = 0f, height: Float = 0f, pickCenter: Boolean = true): PickedObjectList {
        val deferred = pickAsync(x, y, width, height, pickCenter)
        eaglContext?.let { EAGLContext.setCurrentContext(it) }
        // Drain pick frames inline so the deferred completes before we return.
        while (pickQueue.isNotEmpty()) {
            val pickFrame = pickQueue.removeFirst()
            try {
                engine.drawFrame(pickFrame)
            } catch (e: Exception) {
                logMessage(ERROR, "WorldWindow", "pick", "Exception during pick draw", e)
            } finally {
                pickFrame.recycle()
            }
        }
        return if (deferred.isCompleted) deferred.getCompleted() else PickedObjectList()
    }

    fun pickShapesInRect(x: Float, y: Float, width: Float, height: Float): PickedObjectList =
        pick(x, y, width, height, pickCenter = false)

    fun unmarkResourceAbsent(resourceId: Int) {
        engine.renderResourceCache.absentResourceList.unmarkResourceAbsent(resourceId)
    }

    fun requestRedraw() {
        if (!isWaitingForRedraw && !engine.viewport.isEmpty) {
            displayLink?.paused = false
            isWaitingForRedraw = true
        }
    }

    /** Idempotent. Hosts call from "did enter background"; GL context survives, textures stay
     *  uploaded across resume. */
    fun pauseRendering() {
        displayLink?.paused = true
        isWaitingForRedraw = false
    }

    fun resumeRendering() {
        requestRedraw()
    }

    /** CADisplayLink tick. Main thread. */
    @ObjCAction
    fun doFrame(sender: CADisplayLink) {
        val ctx = eaglContext ?: run { sender.paused = true; return }
        if (framebuffer == 0u) return
        // SwiftUI NavigationSplitView momentarily sizes the hosted UIView to W×0/0×H during
        // animations; presenting against that floods the console with IOSurface allocation warnings.
        val lw = layer.bounds.useContents { size.width }
        val lh = layer.bounds.useContents { size.height }
        if (lw <= 0.0 || lh <= 0.0) return
        if (frameQueue.size >= MAX_FRAME_QUEUE_SIZE) return

        isWaitingForRedraw = false
        EAGLContext.setCurrentContext(ctx)
        try {
            renderFrame(Frame.obtain(framePool))
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "doFrame", "Exception while rendering frame", e)
        }

        // Bind our screen FBO before the engine draws; engine sub-passes that rebind
        // KglFramebuffer.NONE end up here via IosKgl's remap.
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), framebuffer)
        glViewport(0, 0, fbWidth, fbHeight)
        drainFrameQueue()

        glBindRenderbuffer(GL_RENDERBUFFER.toUInt(), colorRenderbuffer)
        ctx.presentRenderbuffer(GL_RENDERBUFFER.toULong())

        if (!isWaitingForRedraw) sender.paused = true
    }

    private fun renderFrame(frame: Frame) {
        if (engine.renderFrame(frame)) requestRedraw()
        if (frame.isPickMode) pickQueue.addLast(frame) else frameQueue.addLast(frame)
        if (!frame.isPickMode) {
            navigatorEvents.onFrameRendered(frame.modelview, frame.projection, engine.globe.elevationModel.timestamp)
        }
    }

    private fun drainFrameQueue() {
        // Pick frames first — picking is synchronous and resolves a Deferred.
        while (pickQueue.isNotEmpty()) {
            val pickFrame = pickQueue.removeFirst()
            try {
                engine.drawFrame(pickFrame)
            } catch (e: Exception) {
                logMessage(ERROR, "WorldWindow", "drainFrameQueue", "Exception during pick draw", e)
            } finally {
                pickFrame.recycle()
            }
        }
        if (frameQueue.isNotEmpty()) {
            val nextFrame = frameQueue.removeFirst()
            currentFrame?.recycle()
            currentFrame = nextFrame
        }
        try {
            currentFrame?.let { engine.drawFrame(it) }
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "drainFrameQueue", "Exception during draw", e)
        }
    }

    private fun clearFrameQueue() {
        while (pickQueue.isNotEmpty()) pickQueue.removeFirst().recycle()
        while (frameQueue.isNotEmpty()) frameQueue.removeFirst().recycle()
        currentFrame?.recycle()
        currentFrame = null
    }

    // ------------- UITouch dispatch -------------

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        controller.cancelFling()
        dispatchTouches(touches, isStart = true, isEnd = false, isCancel = false)
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        dispatchTouches(touches, isStart = false, isEnd = false, isCancel = false)
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        dispatchTouches(touches, isStart = false, isEnd = true, isCancel = false)
    }

    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) {
        dispatchTouches(touches, isStart = false, isEnd = false, isCancel = true)
    }

    /** UIKit hands us only the *changed* touches per callback, but the gesture math needs the
     *  full down-set for centroids - so we track that in [touchToPointerId] ourselves. */
    @Suppress("UNCHECKED_CAST")
    private fun dispatchTouches(touches: Set<*>, isStart: Boolean, isEnd: Boolean, isCancel: Boolean) {
        val changed = touches as Set<UITouch>
        if (isStart) {
            for (t in changed) if (t !in touchToPointerId) touchToPointerId[t] = nextPointerId++
        }

        val allDown = touchToPointerId.keys.toMutableList()
        val primaryChanged = changed.firstOrNull()

        val action = when {
            isCancel -> ACTION_CANCEL
            isStart && allDown.size == changed.size -> ACTION_DOWN
            isStart -> ACTION_POINTER_DOWN
            isEnd && allDown.size == changed.size -> ACTION_UP
            isEnd -> ACTION_POINTER_UP
            else -> ACTION_MOVE
        }

        val ids = IntArray(allDown.size)
        val xs = FloatArray(allDown.size)
        val ys = FloatArray(allDown.size)
        val scale = contentScaleFactor.toFloat()
        var actionIndex = 0
        for (i in allDown.indices) {
            val t = allDown[i]
            ids[i] = touchToPointerId[t] ?: -1
            t.locationInView(this).useContents {
                xs[i] = (x * scale).toFloat()
                ys[i] = (y * scale).toFloat()
            }
            if (t === primaryChanged) actionIndex = i
        }

        // Native UIGestureRecognizer cancellations can deliver `changed` touches that are
        // already removed from the tracked set; bail out before xs[0] throws IOOB.
        if (ids.isEmpty()) {
            if (isEnd || isCancel) for (t in changed) touchToPointerId.remove(t)
            if (touchToPointerId.isEmpty()) nextPointerId = 0
            return
        }

        touchEvent.set(action, actionIndex, ids, xs, ys)
        try {
            if (!selectDragDetector.onTouchEvent(touchEvent) &&
                controller.onTouchEvent(touchEvent)) navigatorEvents.onTouchEvent(touchEvent)
        } catch (e: Exception) {
            logMessage(ERROR, "WorldWindow", "dispatchTouches", "Exception in controller dispatch", e)
        }

        // Drop ended/cancelled touches AFTER dispatch so the lifting touch shows up in the
        // final centroid.
        if (isEnd || isCancel) for (t in changed) touchToPointerId.remove(t)
        if (touchToPointerId.isEmpty()) nextPointerId = 0
    }
}

private const val MAX_FRAME_QUEUE_SIZE = 2
