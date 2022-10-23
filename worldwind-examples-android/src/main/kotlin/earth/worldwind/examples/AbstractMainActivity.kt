package earth.worldwind.examples

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

/**
 * This abstract Activity class implements a Navigation Drawer menu shared by all the WorldWind Example activities.
 */
abstract class AbstractMainActivity: AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    protected lateinit var drawerToggle: ActionBarDrawerToggle
    protected lateinit var navigationView: NavigationView
    protected lateinit var aboutBoxTitle: String
    protected lateinit var aboutBoxText: String
    /**
     * Returns a reference to the WorldWindow.
     *
     *
     * Derived classes must implement this method.
     *
     * @return The WorldWindow GLSurfaceView object
     */
    protected abstract val wwd: WorldWindow

    /**
     * This method should be called by derived classes in their onCreate method.
     *
     * @param layoutResID Resource ID to be inflated.
     */
    override fun setContentView(@LayoutRes layoutResID: Int) {
        super.setContentView(layoutResID)
        onCreateDrawer()
    }

    protected open fun onCreateDrawer() {
        // Add support for a Toolbar and set to act as the ActionBar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Add support for the navigation drawer full of examples
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawerToggle = ActionBarDrawerToggle(
            this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawer.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        navigationView = findViewById(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener(this)
        navigationView.setCheckedItem(selectedItemId)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START) else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Update the menu by highlighting the last selected menu item
        navigationView.setCheckedItem(selectedItemId)
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
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        if (id == R.id.action_about) {
            showAboutBox()
            return true
        }
        return super.onOptionsItemSelected(item)
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

    /**
     * This method is invoked when the About button is selected in the Options menu.
     */
    protected open fun showAboutBox() {
        val alertDialogBuilder = AlertDialog.Builder(this)
            .setTitle(aboutBoxTitle)
            .setMessage(aboutBoxText)
            .setCancelable(true)
            .setNegativeButton("Close") { dialog: DialogInterface, _: Int ->
                // if this button is clicked, just close
                // the dialog box and do nothing
                dialog.cancel()
            }
        alertDialogBuilder.create().show()
    }

    protected open fun printMetrics() {
        // Assemble the current system memory info.
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        // Assemble the current WorldWind frame metrics.
        val fm = wwd.engine.frameMetrics as BasicFrameMetrics

        // Print a log message with the system memory, WorldWind cache usage, and WorldWind average frame time.
        log(
            Logger.INFO, "System memory %,.0f KB    Heap memory %,.0f KB    Render cache %,.0f KB    Frame time %.1f ms + %.1f ms".format(
                (mi.totalMem - mi.availMem) / 1024.0,
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0,
                fm.renderResourceCacheUsedCapacity / 1024.0,
                fm.renderTimeAverage,
                fm.drawTimeAverage
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

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle.onConfigurationChanged(newConfig)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Persist the selected item between Activities
        selectedItemId = item.itemId

        // Handle navigation view item clicks here.
        when (selectedItemId) {
            R.id.nav_basic_performance_benchmark_activity -> startActivity(Intent(applicationContext, BasicPerformanceBenchmarkActivity::class.java))
            R.id.nav_basic_stress_test_activity -> startActivity(Intent(applicationContext, BasicStressTestActivity::class.java))
            R.id.nav_day_night_cycle_activity -> startActivity(Intent(applicationContext, DayNightCycleActivity::class.java))
            R.id.nav_general_globe_activity -> startActivity(Intent(applicationContext, GeneralGlobeActivity::class.java))
            R.id.nav_mgrs_graticule_activity -> startActivity(Intent(applicationContext, MGRSGraticuleActivity::class.java))
            R.id.nav_multi_globe_activity -> startActivity(Intent(applicationContext, MultiGlobeActivity::class.java))
            R.id.nav_omnidirectional_sightline_activity -> startActivity(Intent(applicationContext, OmnidirectionalSightlineActivity::class.java))
            R.id.nav_paths_example -> startActivity(Intent(applicationContext, PathsExampleActivity::class.java))
            R.id.nav_paths_and_polygons_activity -> startActivity(Intent(applicationContext, PathsPolygonsLabelsActivity::class.java))
            R.id.nav_placemarks_demo_activity -> startActivity(Intent(applicationContext, PlacemarksDemoActivity::class.java))
            R.id.nav_placemarks_milstd2525_activity -> startActivity(Intent(applicationContext, PlacemarksMilStd2525Activity::class.java))
            R.id.nav_placemarks_milstd2525_demo_activity -> startActivity(Intent(applicationContext, PlacemarksMilStd2525DemoActivity::class.java))
            R.id.nav_placemarks_milstd2525_stress_activity -> startActivity(Intent(applicationContext, PlacemarksMilStd2525StressActivity::class.java))
            R.id.nav_placemarks_select_drag_activity -> startActivity(Intent(applicationContext, PlacemarksSelectDragActivity::class.java))
            R.id.nav_placemarks_stress_activity -> startActivity(Intent(applicationContext, PlacemarksStressTestActivity::class.java))
            R.id.nav_texture_stress_test_activity -> startActivity(Intent(applicationContext, TextureStressTestActivity::class.java))
        }
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawer.closeDrawer(GravityCompat.START)
        return true
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
        protected const val PRINT_METRICS_DELAY = 3000L
        protected val sessionTimestamp = Date().time
        protected var selectedItemId = R.id.nav_general_globe_activity
    }
}