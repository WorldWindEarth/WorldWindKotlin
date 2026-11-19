package earth.worldwind.tutorials

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

class SensorFootprintFragment: BasicGlobeFragment() {
    private lateinit var tutorial: SensorFootprintTutorial

    /**
     * Creates a new WorldWindow (GLSurfaceView) object with drones drawing their sensor frames.
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also {
        tutorial = SensorFootprintTutorial(it.engine)
        tutorial.start()
    }

    /** Adds the tutorial's drone-count actions as buttons over the globe, bottom left. */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val rootView = super.onCreateView(inflater, container, savedInstanceState) ?: return null
        val globe = rootView.findViewById<FrameLayout>(R.id.globe)
        val buttons = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            for (action in tutorial.actions) addView(Button(context).apply {
                text = action
                setOnClickListener { tutorial.runAction(action) }
            })
        }
        globe.addView(buttons, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(24, 24, 24, 24) })
        return rootView
    }
}
