package earth.worldwind.perftest

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.LayoutRes
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import earth.worldwind.WorldWindow
import earth.worldwind.frame.BasicFrameMetrics
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.navigator.NavigatorAction
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * This abstract Activity class implements a Navigation Drawer menu shared by all the WorldWind Example activities.
 */
abstract class AbstractMainActivity: AppCompatActivity() {//}, NavigationView.OnNavigationItemSelectedListener {
    /**
     * Returns a reference to the WorldWindow.
     *
     *
     * Derived classes must implement this method.
     *
     * @return The WorldWindow GLSurfaceView object
     */
    protected abstract val wwd: WorldWindow

    protected lateinit var renderTimeView: TextView
    protected lateinit var drawTimeView: TextView
    protected lateinit var systemMemView: TextView
    protected lateinit var heapMemView: TextView
    protected lateinit var perfOverlay: ViewGroup

    data class Metrics(val rt: Double, val dt: Double, val sm: Double, val hm: Double, val rcuc: Double, val rcec: Int)

    private val metrics = mutableListOf<Metrics>()
/**

     * This method should be called by derived classes in their onCreate method.
     *
     * @param layoutResID Resource ID to be inflated.
     */
    override fun setContentView(@LayoutRes layoutResID: Int) {
        super.setContentView(layoutResID)
        // Add support for a Toolbar and set to act as the ActionBar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Initialize the UI elements that we'll update upon the navigation events
        perfOverlay = findViewById(R.id.perf_status)
        perfOverlay.visibility = View.VISIBLE
        renderTimeView = findViewById(R.id.rendertime_value)
        drawTimeView = findViewById(R.id.drawtime_value)
        systemMemView = findViewById(R.id.sysmem_value)
        heapMemView = findViewById(R.id.heapmem_value)
    }

    override fun onResume() {
        super.onResume()
        // Periodically print the FrameMetrics.
        lifecycleScope.launch {
            delay(PRINT_METRICS_DELAY)
            printMetrics()
        }
        // Restore camera state from previously saved session data
        restoreCameraState()
    }

    override fun onPause() {
        super.onPause()
        // Cancel all async tasks in the activity scope
        lifecycleScope.coroutineContext.cancelChildren()
        // Save camera state.
        saveCameraState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    /**
     * Saves camera state to a SharedPreferences object.
     */
    protected open fun saveCameraState() {
        val preferences = getPreferences(MODE_PRIVATE)
        val editor = preferences.edit()

        // Write an identifier to the preferences for this session;
        editor.putLong(SESSION_TIMESTAMP, sessionTimestamp)

        // Write the camera data
        val camera = wwd.engine.camera
        editor.putFloat(CAMERA_LATITUDE, camera.position.latitude.inDegrees.toFloat())
        editor.putFloat(CAMERA_LONGITUDE, camera.position.longitude.inDegrees.toFloat())
        editor.putFloat(CAMERA_ALTITUDE, camera.position.altitude.toFloat())
        editor.putInt(CAMERA_ALTITUDE_MODE, camera.altitudeMode.ordinal)
        editor.putFloat(CAMERA_HEADING, camera.heading.inDegrees.toFloat())
        editor.putFloat(CAMERA_TILT, camera.tilt.inDegrees.toFloat())
        editor.putFloat(CAMERA_ROLL, camera.roll.inDegrees.toFloat())
        editor.putFloat(CAMERA_FOV, camera.fieldOfView.inDegrees.toFloat())
        editor.apply()
    }

    /**
     * Restores camera state from a SharedPreferences object.
     */
    protected open fun restoreCameraState() {
        val preferences = getPreferences(MODE_PRIVATE)

        // We only want to restore preferences from the same session.
        if (preferences.getLong(SESSION_TIMESTAMP, -1) != sessionTimestamp) return
        // Read the camera data
        val lat = preferences.getFloat(CAMERA_LATITUDE, Float.MAX_VALUE)
        val lon = preferences.getFloat(CAMERA_LONGITUDE, Float.MAX_VALUE)
        val alt = preferences.getFloat(CAMERA_ALTITUDE, Float.MAX_VALUE)
        val altMode = preferences.getInt(CAMERA_ALTITUDE_MODE, AltitudeMode.ABSOLUTE.ordinal)
        val heading = preferences.getFloat(CAMERA_HEADING, Float.MAX_VALUE)
        val tilt = preferences.getFloat(CAMERA_TILT, Float.MAX_VALUE)
        val roll = preferences.getFloat(CAMERA_ROLL, Float.MAX_VALUE)
        val fov = preferences.getFloat(CAMERA_FOV, Float.MAX_VALUE)

        if (lat == Float.MAX_VALUE || lon == Float.MAX_VALUE || alt == Float.MAX_VALUE
            || heading == Float.MAX_VALUE || tilt == Float.MAX_VALUE || roll == Float.MAX_VALUE) return

        // Restore the camera state.
        wwd.engine.camera.set(
            lat.toDouble().degrees, lon.toDouble().degrees, alt.toDouble(), AltitudeMode.values()[altMode],
            heading.toDouble().degrees, tilt.toDouble().degrees, roll.toDouble().degrees, fov.toDouble().degrees
        )
    }

    protected open fun printMetrics() {
        // Assemble the current system memory info.
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        // Assemble the current WorldWind frame metrics.
        val fm = wwd.engine.frameMetrics as BasicFrameMetrics

        val rt = fm.renderTimeAverage
        val dt = fm.drawTimeAverage
        val sm = (mi.totalMem - mi.availMem) / (1024.0 * 1024.0)
        val hm = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0)
        val rcuc = fm.renderResourceCacheUsedCapacity / (1024.0 * 1024.0)
        val rcec = fm.renderResourceCacheEntryCount

        metrics.add(Metrics(rt, dt, sm, hm, rcuc, rcec))

        renderTimeView.text = "RT: %4.2f ms".format(rt)
        drawTimeView.text = "DT: %4.2f ms".format(dt)
        systemMemView.text = "SM: %5.3f MB".format(sm)
        heapMemView.text = "HM: %5.3f MB".format(hm)
        renderTimeView.setTextColor(if (rt < 35.0) Color.GREEN else Color.YELLOW)
        drawTimeView.setTextColor(if (dt < 35.0) Color.GREEN else Color.YELLOW)
        systemMemView.setTextColor(Color.YELLOW)
        heapMemView.setTextColor(Color.YELLOW)

        // Print a log message with the system memory, WorldWind cache usage, and WorldWind average frame time.
        log(
            Logger.DEBUG, "System memory %,.3f MB    Heap memory %,.3f MB    Render cache %,.3f MB    Frame time %.1f ms + %.1f ms".format(
                sm, hm, rcuc, rt, dt
            )
        )

        // Reset the accumulated WorldWind frame metrics.
        fm.reset()

        // Print the frame metrics again after the configured delay.
        lifecycleScope.launch {
            delay(PRINT_METRICS_DELAY)
            printMetrics()
        }
    }

    protected fun dumpMetrics() {
        val path = getExternalFilesDir(null)
        val csvDirectory = File(path, "csv")
        csvDirectory.mkdirs()
        val dateTime =
            SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime())
        val file = File(csvDirectory, "ProfileRun_${dateTime}.csv")
        val writer = file.printWriter()
        writer.println("RenderThreadTime,DrawThreadTime,SystemMemoryMB,HeapMemoryMB,ResourceCacheUsageSizeMB,ResourceCacheEntryCount")
        metrics.forEach { e -> writer.println("${e.rt},${e.dt},${e.sm},${e.hm},${e.rcuc},${e.rcec}") }
        writer.close()
    }

    companion object {
        protected const val SESSION_TIMESTAMP = "session_timestamp"
        protected const val CAMERA_LATITUDE = "latitude"
        protected const val CAMERA_LONGITUDE = "longitude"
        protected const val CAMERA_ALTITUDE = "altitude"
        protected const val CAMERA_ALTITUDE_MODE = "altitude_mode"
        protected const val CAMERA_HEADING = "heading"
        protected const val CAMERA_TILT = "tilt"
        protected const val CAMERA_ROLL = "roll"
        protected const val CAMERA_FOV = "fov"
        protected const val PRINT_METRICS_DELAY = 100L
        protected val sessionTimestamp = Date().time
    }
}