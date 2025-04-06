package earth.worldwind.examples

import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.milstd2525.MilStd2525
import earth.worldwind.shape.milstd2525.MilStd2525Placemark
import earth.worldwind.shape.milstd2525.MilStd2525TacticalGraphic

open class PlacemarksMilStd2525Activity: GeneralGlobeActivity() {
    protected lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_placemarks_milstd2525)
        aboutBoxText = "Demonstrates how to add MilStd2525C Symbols to a RenderableLayer."

        // Create a TextView to show the MIL-STD-2525 renderer's initialization status
        statusText = TextView(this)
        statusText.setTextColor(Color.YELLOW)
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(statusText)

        // Set the camera to look at the area where the symbols will be displayed.
        val pos = fromDegrees(32.420, 63.414, 0.0)
        val lookAt = LookAt(
            position = pos,
            altitudeMode = AltitudeMode.ABSOLUTE,
            range = 2e4,
            heading = ZERO,
            tilt = 60.0.degrees,
            roll = ZERO
        )
        wwd.engine.cameraFromLookAt(lookAt)

        // The MIL-STD-2525 rendering library takes time initialize, we'll perform this task via the
        // async task's background thread and then load the symbols in its post execute handler.
        InitializeSymbolsTask().execute()
    }

    /**
     * InitializeSymbolsTask is an async task that initializes the MIL-STD-2525 symbol renderer on a background thread
     * and then loads the symbols after the initialization is complete. This task must be instantiated and executed on
     * the UI Thread.
     */
    protected open inner class InitializeSymbolsTask : CoroutinesAsyncTask<Void, Void, Void>() {
        override fun onPreExecute() {
            super.onPreExecute()
            statusText.text = "Initializing the MIL-STD-2525 Library and symbols..."
        }

        /**
         * Initialize the MIL-STD-2525 Rendering Library on a background thread.
         */
        override fun doInBackground(vararg params: Void): Void? {
            // Time consuming operation . . .
            MilStd2525.initializeRenderer(applicationContext)
            return null
        }

        /**
         * Update the symbol layer on the UI Thread.
         */
        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)

            // Create a layer for the military symbols and add it to the WorldWindow
            val symbolLayer = RenderableLayer("Symbols")
            wwd.engine.layers.addLayer(symbolLayer)

            // Add a "MIL-STD-2525 Feint/Dummy Friendly SOF Drone Aircraft from US"
            val drone = Placemark(
                fromDegrees(32.4520, 63.44553, 3000.0),
                MilStd2525Placemark.getPlacemarkAttributes("10030101001103002700", mapOf("Q" to "235", "AS" to "US"))
            )
            drone.attributes.isDrawLeader = true
            drone.isBillboardingEnabled = true
            symbolLayer.addRenderable(drone)

            // Add a "MIL-STD-2525 Present/Destroyed Hostile Self-Propelled Multiple Rocket Launcher"
            val launcher = Placemark(
                fromDegrees(32.4014, 63.3894, 0.0),
                MilStd2525Placemark.getPlacemarkAttributes("10061040001303014100", mapOf("Q" to "90", "AJ" to "0.1"))
            )
            launcher.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            launcher.isBillboardingEnabled = true
            symbolLayer.addRenderable(launcher)

            // Add a "MIL-STD-2525 Friendly Heavy Machine Gun on Railway"
            val machineGun = Placemark(
                fromDegrees(32.3902, 63.4161, 0.0),
                MilStd2525Placemark.getPlacemarkAttributes("10031500361102030000", mapOf(
                    "C" to "200",
                    "G" to "FOR REINFORCEMENTS",
                    "H" to "ADDED SUPPORT FOR JJ",
                    "V" to "MACHINE GUN",
                    "W" to "30140000ZSEP97", // Date/Time Group
                    "AS" to "US"
            ))
            )
            machineGun.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            machineGun.isBillboardingEnabled = true
            symbolLayer.addRenderable(machineGun)

            // Add "MIL-STD-2525 Counterattack by fire"
            val counterattack = MilStd2525TacticalGraphic(
                "10062500003407000000", listOf(
                    Location.fromDegrees(32.379, 63.457),
                    Location.fromDegrees(32.348, 63.412),
                    Location.fromDegrees(32.364, 63.375),
                    Location.fromDegrees(32.396, 63.451),
                )
            )
            symbolLayer.addRenderable(counterattack)

            // Signal a change in the WorldWind scene; requestRedraw() is callable from any thread.
            wwd.requestRedraw()

            // Clear the status message set in onPreExecute
            statusText.text = ""
        }
    }
}