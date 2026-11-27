package earth.worldwind.tutorials

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import earth.worldwind.layer.CompassLayer
import earth.worldwind.layer.WorldMapLayer
import earth.worldwind.render.Color

/**
 * Sensor frames: 100 drones, with a panel on the right to switch what is drawn while the drones
 * fly, and the measurements above it, refreshed twice a second.
 */
class SensorFootprintFragment: BasicGlobeFragment() {
    private lateinit var tutorial: SensorFootprintTutorial
    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private val refresh = object : Runnable {
        override fun run() {
            statusView?.text = tutorial.status
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Creates a new WorldWindow (GLSurfaceView) object with drones drawing their sensor frames.
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also {
        // The panel covers the compass's top right corner and the measurements the world map's top left
        it.engine.layers.run { removeAllLayers(filter { layer -> layer is CompassLayer || layer is WorldMapLayer }) }
        tutorial = SensorFootprintTutorial(it.engine)
        tutorial.isStatusOnGlobe = false
        tutorial.start()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val rootView = super.onCreateView(inflater, container, savedInstanceState) ?: return null
        val globe = rootView.findViewById<FrameLayout>(R.id.globe)
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val status = TextView(context).apply {
            setTextColor(AndroidColor.WHITE)
            setShadowLayer(3f, 0f, 0f, AndroidColor.BLACK)
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        statusView = status
        globe.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ))

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTextColor(AndroidColor.WHITE)
            textSize = 13f
        }
        fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(context).apply {
            this.text = text
            setTextColor(AndroidColor.WHITE)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }.also { panel.addView(it) }
        fun slider(text: String, value: Float, onChange: (Float) -> Unit) {
            val caption = label("$text ${(value * 100).toInt()} %")
            panel.addView(caption)
            panel.addView(SeekBar(context).apply {
                max = 100
                progress = (value * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        caption.text = "$text $progress %"
                        onChange(progress / 100f)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            })
        }
        fun colors(selected: Color, onChange: (Color) -> Unit) {
            val names = SensorFootprintTutorial.COLORS.map { it.first }
            panel.addView(Spinner(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(SensorFootprintTutorial.COLORS.indexOfFirst { it.second == selected }.coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                        (view as? TextView)?.setTextColor(AndroidColor.WHITE)
                        onChange(Color(SensorFootprintTutorial.COLORS[position].second))
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            })
        }

        val t = tutorial
        switch("Frustum frame", t.isFrameShown) { t.isFrameShown = it }
        switch("Straight sides", t.isStraightSidesShown) { t.isStraightSidesShown = it }
        switch("Seen tint", t.isSeenShown) { t.isSeenShown = it }
        colors(t.seenColor) { t.seenColor = it }
        slider("Seen opacity", t.seenOpacity) { t.seenOpacity = it }
        switch("Hidden tint", t.isHiddenShown) { t.isHiddenShown = it }
        colors(t.hiddenColor) { t.hiddenColor = it }
        slider("Hidden opacity", t.hiddenOpacity) { t.hiddenOpacity = it }
        switch("Picture", t.isPictureShown) { t.isPictureShown = it }
        slider("Picture opacity", t.pictureOpacity) { t.pictureOpacity = it }
        switch("Elevation outline", t.isElevationOutlineShown) { t.isElevationOutlineShown = it }
        switch("Occlusion", t.isOcclusionEnabled) { t.isOcclusionEnabled = it }
        panel.addView(label("Drones (atlas holds ${t.atlasCells} occluded)"))
        panel.addView(RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            for (count in SensorFootprintTutorial.DRONE_COUNTS) addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = "$count"
                setTextColor(AndroidColor.WHITE)
                isChecked = count == t.droneCount
                setOnCheckedChangeListener { _, checked -> if (checked) t.setDroneCount(count) }
            })
        })

        val scroll = ScrollView(context).apply {
            setBackgroundColor(AndroidColor.argb(160, 0, 0, 0))
            addView(panel)
        }
        // Ends above the activity's floating button in the bottom right corner
        globe.addView(scroll, FrameLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            bottomMargin = dp(80)
        })
        globe.addView(Button(context).apply {
            text = "Panel"
            setOnClickListener { scroll.visibility = if (scroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        // Beside the panel, clear of the view controls in the bottom left corner
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(dp(8), dp(8), dp(238), dp(8)) })
        return rootView
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
