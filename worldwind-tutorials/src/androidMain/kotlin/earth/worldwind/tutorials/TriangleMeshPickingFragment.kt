package earth.worldwind.tutorials

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.PickedRenderablePoint
import earth.worldwind.WorldWindow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

class TriangleMeshPickingFragment : BasicGlobeFragment() {
    private data class HoverPick(val x: Float, val y: Float, val token: Long)

    private lateinit var statusView: TextView
    private lateinit var tutorial: TriangleMeshPickingTutorial
    private lateinit var pickController: PickNavigateController

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()
        tutorial = TriangleMeshPickingTutorial(wwd.engine)
        tutorial.start()
        pickController = PickNavigateController(wwd, tutorial)
        wwd.controller = pickController
        return wwd
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val globeLayout = view.findViewById<FrameLayout>(R.id.globe)
        val density = resources.displayMetrics.density
        val horizontalPadding = (16 * density).toInt()
        val verticalPadding = (13 * density).toInt()
        val inset = (16 * density).toInt()
        statusView = TextView(requireContext()).apply {
            setBackgroundColor(Color.argb(150, 10, 16, 24))
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            elevation = 8f
            maxWidth = (320 * density).toInt()
            text = tutorial.statusText
        }
        globeLayout.addView(statusView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            marginStart = inset
            topMargin = inset
        })
        wwd.setOnGenericMotionListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    pickController.updateHoverSelection(event.x, event.y)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    pickController.clearHoverSelection()
                }
            }
            false
        }
    }

    open inner class PickNavigateController(
        wwd: WorldWindow,
        private val tutorial: TriangleMeshPickingTutorial,
    ) : BasicWorldWindowController(wwd) {
        private var pickRequest: Deferred<PickedRenderablePoint?>? = null
        private var tapX = 0f
        private var tapY = 0f
        private var hoverPickInFlight = false
        private var hoverPickToken = 0L
        private var queuedHoverPick: HoverPick? = null

        private val pickGestureDetector = GestureDetector(
            requireContext().applicationContext,
            object : SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean {
                    tapX = event.x
                    tapY = event.y
                    pickRequest = wwd.pickMeshPointAsync(event.x, event.y, forceDepthPointPick = true)
                    return false
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    applySelection(tapX, tapY, pickRequest)
                    return false
                }
            }
        )

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val consumed = pickGestureDetector.onTouchEvent(event)
            return if (!consumed) super.onTouchEvent(event) else true
        }

        fun updateHoverSelection(x: Float, y: Float) {
            queuedHoverPick = HoverPick(x, y, ++hoverPickToken)
            if (!hoverPickInFlight) dispatchHoverPick()
        }

        fun clearHoverSelection() {
            queuedHoverPick = null
            hoverPickToken++
            tutorial.clearPickFeedback()
            statusView.text = tutorial.statusText
            wwd.requestRedraw()
        }

        private fun dispatchHoverPick() {
            val hoverPick = queuedHoverPick ?: return
            queuedHoverPick = null
            hoverPickInFlight = true
            val hoverRequest = wwd.pickMeshPointAsync(hoverPick.x, hoverPick.y, forceDepthPointPick = true)
            wwd.engine.renderResourceCache.mainScope.launch {
                val meshPick = hoverRequest.await()
                val terrainPosition = if (meshPick == null) {
                    tutorial.pickTerrainPosition(hoverPick.x.toDouble(), hoverPick.y.toDouble())
                } else null
                if (hoverPick.token == hoverPickToken) {
                    tutorial.handlePick(meshPick, terrainPosition)
                    statusView.text = tutorial.statusText
                    wwd.requestRedraw()
                }
                hoverPickInFlight = false
                if (queuedHoverPick != null) dispatchHoverPick()
            }
        }

        private fun applySelection(x: Float, y: Float, pickRequest: Deferred<PickedRenderablePoint?>?) {
            wwd.engine.renderResourceCache.mainScope.launch {
                val meshPick = pickRequest?.await()
                val terrainPosition = if (meshPick == null) tutorial.pickTerrainPosition(x.toDouble(), y.toDouble()) else null
                tutorial.handlePick(meshPick, terrainPosition)
                statusView.text = tutorial.statusText
                wwd.requestRedraw()
            }
        }
    }
}
