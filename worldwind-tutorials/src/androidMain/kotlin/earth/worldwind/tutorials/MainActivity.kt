package earth.worldwind.tutorials

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import earth.worldwind.WorldWindow
import earth.worldwind.frame.BasicFrameMetrics
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * This abstract Activity class implements a Navigation Drawer menu shared by all the WorldWind Example activities.
 */
class MainActivity: AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var navigationView: NavigationView
    private var twoPaneView = false
    private var tutorialUrl: String? = null
    private val aboutBoxTitle = "WorldWind Tutorials" // TODO: use a string resource, e.g., app name
    private val aboutBoxText = "A collection of tutorials" // TODO: make this a string resource

    /**
     * Returns a reference to the WorldWindow.
     *
     * Derived classes must implement this method.
     *
     * @return The WorldWindow GLSurfaceView object
     */
    val wwd: WorldWindow? get() = null // TODO: Implement via Fragment Manager and findFragmentById

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onCreateDrawer()
        if (findViewById<View?>(R.id.code_container) != null) {
            // The code container view will be present only in the
            // large-screen layouts (res/values-w900dp).
            // If this view is present, then the
            // activity should be in two-pane mode.
            twoPaneView = true
        }
        if (!twoPaneView) {
            val codeViewButton = findViewById<FloatingActionButton>(R.id.fab)
            codeViewButton.visibility = View.VISIBLE // is set to GONE in layout
            codeViewButton.setOnClickListener { view: View ->
                val context = view.context
                val intent = Intent(context, CodeActivity::class.java)
                val bundle = Bundle()
                bundle.putString("url", tutorialUrl)
                intent.putExtra("arguments", bundle)
                context.startActivity(intent)
            }
        }
        if (savedInstanceState == null) {
            // savedInstanceState is non-null when there is fragment state
            // saved from previous configurations of this activity
            // (e.g. when rotating the screen from portrait to landscape).
            // In this case, the fragment will automatically be re-added
            // to its container so we don't need to manually add it.
            // For more information, see the Fragments API guide at:
            //
            // http://developer.android.com/guide/components/fragments.html
            //
            loadTutorial(BasicGlobeFragment::class.java, "file:///android_asset/basic_globe_tutorial.html", R.string.title_basic_globe)
        }
    }

    private fun onCreateDrawer() {
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
        // Use this Activity's Handler to periodically print the FrameMetrics.
        lifecycleScope.launch {
            delay(PRINT_METRICS_DELAY)
            printMetrics()
        }
    }

    override fun onPause() {
        super.onPause()
        // Cancel all async tasks in the activity scope
        lifecycleScope.coroutineContext.cancelChildren()
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
        if (item.itemId == R.id.action_about) {
            showAboutBox()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * This method is invoked when the About button is selected in the Options menu.
     */
    private fun showAboutBox() {
        val alertDialogBuilder = AlertDialog.Builder(this)
        alertDialogBuilder.setTitle(aboutBoxTitle)
        alertDialogBuilder
            .setMessage(aboutBoxText)
            .setCancelable(true)
            // if this button is clicked, just close
            // the dialog box and do nothing
            .setNegativeButton("Close") { dialog: DialogInterface, _: Int -> dialog.cancel() }
        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    private fun printMetrics() {
        // Assemble the current WorldWind frame metrics.
        val fm = wwd?.engine?.frameMetrics as BasicFrameMetrics? ?: return

        // Assemble the current system memory info.
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

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
            R.id.nav_basic_globe_activity -> loadTutorial(
                BasicGlobeFragment::class.java,
                "file:///android_asset/basic_globe_tutorial.html",
                R.string.title_basic_globe
            )
            R.id.nav_camera_view_activity -> loadTutorial(
                CameraViewFragment::class.java,
                "file:///android_asset/camera_view_tutorial.html",
                R.string.title_camera_view
            )
            R.id.nav_camera_control_activity -> loadTutorial(
                CameraControlFragment::class.java,
                "file:///android_asset/camera_control_tutorial.html",
                R.string.title_camera_controls
            )
            R.id.nav_ellipse_activity -> loadTutorial(
                EllipsesFragment::class.java,
                "file:///android_asset/ellipse_tutorial.html",
                R.string.title_ellipses
            )
            R.id.nav_geopackage_activity -> loadTutorial(
                GeoPackageFragment::class.java,
                "file:///android_asset/geopackage_tutorial.html",
                R.string.title_geopackage
            )
            R.id.nav_labels_activity -> loadTutorial(
                LabelsFragment::class.java,
                "file:///android_asset/labels_tutorial.html",
                R.string.title_labels
            )
            R.id.nav_look_at_view_activity -> loadTutorial(
                LookAtViewFragment::class.java,
                "file:///android_asset/look_at_view_tutorial.html",
                R.string.title_look_at_view
            )
            R.id.nav_navigator_event_activity -> loadTutorial(
                NavigatorEventFragment::class.java,
                "file:///android_asset/navigator_events_tutorial.html",
                R.string.title_navigator_event
            )
            R.id.nav_omnidirectional_sightline_activity -> loadTutorial(
                OmnidirectionalSightlineFragment::class.java,
                "file:///android_asset/omnidirectional_sightline_tutorial.html",
                R.string.title_omni_sightline
            )
            R.id.nav_elevation_heatmap_activity -> loadTutorial(
                ElevationHeatmapFragment::class.java,
                "file:///android_asset/elevation_heatmap_tutorial.html",
                R.string.title_elevation_heatmap
            )
            R.id.nav_paths_activity -> loadTutorial(
                PathsFragment::class.java,
                "file:///android_asset/paths_tutorial.html",
                R.string.title_paths
            )
            R.id.nav_placemarks_activity -> loadTutorial(
                PlacemarksFragment::class.java,
                "file:///android_asset/placemarks_tutorial.html",
                R.string.title_placemarks
            )
            R.id.nav_placemarks_picking_activity -> loadTutorial(
                PlacemarksPickingFragment::class.java,
                "file:///android_asset/placemarks_picking_tutorial.html",
                R.string.title_placemarks_picking
            )
            R.id.nav_polygons_activity -> loadTutorial(
                PolygonsFragment::class.java,
                "file:///android_asset/polygons_tutorial.html",
                R.string.title_polygons
            )
            R.id.nav_shapes_dash_and_fill -> loadTutorial(
                ShapesDashAndFillFragment::class.java,
                "file:///android_asset/shapes_dash_and_fill.html",
                R.string.title_shapes_dash_and_fill
            )
            R.id.nav_show_tessellation_activity -> loadTutorial(
                ShowTessellationFragment::class.java,
                "file:///android_asset/show_tessellation_tutorial.html",
                R.string.title_show_tessellation
            )
            R.id.nav_mgrs_graticule_activity -> loadTutorial(
                MGRSGraticuleFragment::class.java,
                "file:///android_asset/mgrs_graticule_tutorial.html",
                R.string.title_mgrs_graticule
            )
            R.id.nav_gk_graticule_activity -> loadTutorial(
                GKGraticuleFragment::class.java,
                "file:///android_asset/gk_graticule_tutorial.html",
                R.string.title_gk_graticule
            )            
            R.id.nav_surface_image_activity -> loadTutorial(
                SurfaceImageFragment::class.java,
                "file:///android_asset/surface_image_tutorial.html",
                R.string.title_surface_image
            )
            R.id.nav_wms_layer_activity -> loadTutorial(
                WmsLayerFragment::class.java,
                "file:///android_asset/wms_layer_tutorial.html",
                R.string.title_wms_layer
            )
            R.id.nav_wmts_layer_activity -> loadTutorial(
                WmtsLayerFragment::class.java,
                "file:///android_asset/wmts_layer_tutorial.html",
                R.string.title_wmts_layer
            )
            R.id.nav_wcs_elevation_activity -> loadTutorial(
                WcsElevationFragment::class.java,
                "file:///android_asset/wcs_elevation_tutorial.html",
                R.string.title_wcs_elevation_coverage
            )
        }
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawer.closeDrawer(GravityCompat.START)
        return true
    }

    private fun loadTutorial(globeFragment: Class<out Fragment>, url: String, titleId: Int) {
        try {
            setTitle(titleId)
            val globe = globeFragment.newInstance()
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.globe_container, globe) // replace (destroy) existing fragment (if any)
                .commit()
            if (twoPaneView) {
                val bundle = Bundle()
                bundle.putString("url", url)
                val code = CodeFragment()
                code.arguments = bundle
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.code_container, code)
                    .commit()
            } else {
                tutorialUrl = url
            }
        } catch (e: InstantiationException) {
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val PRINT_METRICS_DELAY = 3000L
        private var selectedItemId = R.id.nav_basic_globe_activity
    }
}