package earth.worldwind.perftest

import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import earth.worldwind.WorldWindow
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.BlueMarbleLandsatLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer

/**
 * This activity manifests two side-by-side globes with an adjustable splitter
 */
open class MultiGlobeActivity: AbstractMainActivity() {
    /**
     * This protected member allows derived classes to override the resource used in setContentView.
     */
    protected var layoutResourceId = R.layout.activity_globe
    protected var deviceOrientation = 0
    /**
     * The WorldWindow (GLSurfaceView) maintained by this activity
     */
    protected val worldWindows = mutableListOf<WorldWindow>()
    override val wwd get() = worldWindows[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        // Establish the activity content
        super.onCreate(savedInstanceState)
        setContentView(layoutResourceId)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_multi_globe)
        aboutBoxText = "Demonstrates multiple globes."
        deviceOrientation = resources.configuration.orientation
        performLayout()
    }

    private fun performLayout() {
        releaseWorldWindows()

        // Get the standard/common layout used for a single globe activity
        // and replace it's contents with a multi-globe layout.
        val layout = findViewById<RelativeLayout>(R.id.globe_content)
        layout.removeAllViews()

        // Add the landscape or portrait layout
        val multiGlobeLayout = layoutInflater.inflate(R.layout.multi_globe_content, null)
        layout.addView(multiGlobeLayout)

        // Add a WorldWindow to each of the FrameLayouts in the multi-globe layout.
        val globe1 = findViewById<FrameLayout>(R.id.globe_one)
        val globe2 = findViewById<FrameLayout>(R.id.globe_two)
        val splitter = findViewById<ImageButton>(R.id.splitter)
        globe1.addView(
            if (getWorldWindow(0) == null) createWorldWindow() else getWorldWindow(0),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        globe2.addView(
            if (getWorldWindow(1) == null) createWorldWindow() else getWorldWindow(1),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        splitter.setOnTouchListener(SplitterTouchListener(globe1, globe2, splitter))
    }

    private fun releaseWorldWindows() {
        for (wwd in worldWindows) (wwd.parent as ViewGroup).removeView(wwd)
    }

    private fun createWorldWindow(): WorldWindow {
        // Create the WorldWindow (a GLSurfaceView) which displays the globe.
        val wwd = WorldWindow(this)
        // Setting up the WorldWindow's layers.
        wwd.engine.layers.apply {
            addLayer(BackgroundLayer())
            addLayer(BlueMarbleLandsatLayer())
            addLayer(AtmosphereLayer())
        }
        worldWindows.add(wwd)
        return wwd
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Checks the orientation of the screen
        deviceOrientation = newConfig.orientation
        performLayout()
    }

    override fun onPause() {
        super.onPause()
        for (wwd in worldWindows) wwd.onPause() // pauses the rendering thread
    }

    override fun onResume() {
        super.onResume()
        for (wwd in worldWindows) wwd.onResume() // resumes a paused rendering thread
    }

    fun getWorldWindow(index: Int): WorldWindow? {
        return if (index !in worldWindows.indices) null else worldWindows[index]
    }

    private inner class SplitterTouchListener(
        private val one: FrameLayout,
        private val two: FrameLayout,
        private val splitter: ImageButton
    ): OnTouchListener {
        private val splitterWeight = 30 // TODO: compute this value

        /**
         * Called when a touch event is dispatched to a view. This allows listeners to
         * get a chance to respond before the target view.
         *
         * @param v     The view the touch event has been dispatched to.
         * @param event The MotionEvent object containing full information about
         * the event.
         * @return True if the listener has consumed the event, false otherwise.
         */
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_MOVE) {
                // Get screen coordinates of the touch point
                val rawX = event.rawX
                val rawY = event.rawY
                // Get the primary layout container for the multi-globe display
                val parent = findViewById<LinearLayout>(R.id.multi_globe_content)
                // Get the layoutParams for each of the children. The parent will layout the
                // children based on the layout weights computed based on the splitter position.
                val layout1 = one.layoutParams as LinearLayout.LayoutParams
                val layout2 = two.layoutParams as LinearLayout.LayoutParams
                val layout3 = splitter.layoutParams as LinearLayout.LayoutParams
                val weightSum: Int
                if (deviceOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                    // We're using the pixel values for the layout weights, with a fixed weight
                    // for the splitter.
                    weightSum = parent.width
                    layout1.weight = (rawX - splitterWeight / 2).coerceIn(0f, (weightSum - splitterWeight).toFloat())
                } else {
                    // We're using the pixel values for the layout weights, with a fixed weight
                    // for the splitter.  In portrait mode we have a header that we must account for.
                    val origin = IntArray(2)
                    parent.getLocationOnScreen(origin)
                    val y = rawY - origin[1]
                    weightSum = parent.height
                    layout1.weight = y - (splitterWeight / 2f).coerceIn(0f, (weightSum - splitterWeight).toFloat())
                }
                layout2.weight = (weightSum - layout1.weight - splitterWeight).coerceIn(0f, (weightSum - splitterWeight).toFloat())
                parent.weightSum = weightSum.toFloat()
                layout3.weight = splitterWeight.toFloat()
                one.layoutParams = layout1
                two.layoutParams = layout2
                splitter.layoutParams = layout3
            }
            return false
        }
    }
}