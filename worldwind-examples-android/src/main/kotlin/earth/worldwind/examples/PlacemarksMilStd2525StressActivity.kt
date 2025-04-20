package earth.worldwind.examples

import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.widget.FrameLayout
import android.widget.TextView
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.ShowTessellationLayer
import earth.worldwind.shape.milstd2525.MilStd2525
import earth.worldwind.shape.milstd2525.MilStd2525LevelOfDetailSelector
import earth.worldwind.shape.milstd2525.MilStd2525Placemark
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.asin
import kotlin.random.Random

open class PlacemarksMilStd2525StressActivity: GeneralGlobeActivity(), FrameCallback {
    protected var activityPaused = false
    protected var cameraDegreesPerSecond = 2.0
    protected var lastFrameTimeNanos = 0L

    // A component for displaying the status of this activity
    protected lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_placemarks_milstd2525_stress_test)
        aboutBoxText = "Demonstrates a LOT of different MIL-STD-2525 symbols."

        // Add a TextView on top of the globe to convey the status of this activity
        statusText = TextView(this)
        statusText.setTextColor(Color.YELLOW)
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(statusText)
        wwd.engine.layers.apply {
            clearLayers()
            addLayer(ShowTessellationLayer())
        }

        // The MIL-STD-2525 rendering library takes time initialize, we'll perform this task via the
        // async task's background thread and then load the symbols in its post execute handler.
        InitializeSymbolsTask().execute()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos != 0L) {
            // Compute the frame duration in seconds.
            val frameDurationSeconds = (frameTimeNanos - lastFrameTimeNanos) * 1.0e-9
            val cameraDegrees = frameDurationSeconds * cameraDegreesPerSecond

            // Move the camera to simulate the Earth's rotation about its axis.
            val camera = wwd.engine.camera
            camera.position.longitude = camera.position.longitude.minusDegrees(cameraDegrees)

            // Redraw the WorldWindow to display the above changes.
            wwd.requestRedraw()
        }
        // stop animating when this Activity is paused
        if (!activityPaused) Choreographer.getInstance().postFrameCallback(this)
        lastFrameTimeNanos = frameTimeNanos
    }

    override fun onPause() {
        super.onPause()
        // Stop running the animation when this activity is paused.
        activityPaused = true
        lastFrameTimeNanos = 0
    }

    override fun onResume() {
        super.onResume()
        // Resume the earth rotation animation
        activityPaused = false
        lastFrameTimeNanos = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * InitializeSymbolsTask is an async task that initializes the MIL-STD-2525 symbol renderer on a background thread
     * and then loads the symbols after the initialization is complete. This task must be instantiated and executed on
     * the UI Thread.
     */
    protected open inner class InitializeSymbolsTask: CoroutinesAsyncTask<Void, Void, Void>() {
        // Formatter for a date-time group (DTG) string
        private val dateTimeGroup = SimpleDateFormat("ddHHmmss'Z'MMMyyyy", Locale.US)

        // Create a random number generator with an arbitrary seed
        // that will generate the same numbers between runs.
        protected val random = Random(123)

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

            // Create a Renderable layer for the placemarks and add it to the WorldWindow
            val symbolLayer = RenderableLayer("MIL-STD-2525 Symbols")
            wwd.engine.layers.addLayer(symbolLayer)
            MilStd2525LevelOfDetailSelector.modifiersThreshold = 75e4
            val renderAttributes = mapOf("KEEPUNITRATIO" to "false")
            val version = "100"
            val amplifier = "000"
            val modifiers = "0000"
            var numSymbolsCreated = 0
            for (standardID in standardIdentities) {
                for (symbolSet in symbolSets) {
                    for (status in statusCodes) {
                        val entities = when (symbolSet) {
                            "00" -> unknownEntities
                            "01" -> airEntities
                            "05" -> spaceEntities
                            "10" -> landUnitEntities
                            "15" -> landEquipmentEntities
                            "20" -> landInstallationEntities
                            "30" -> seaSurfaceEntities
                            "35" -> subsurfaceEntities
                            "36" -> mineWarfareEntities
                            else -> error("Unsupported Symbol Set")
                        }
                        for (entity in entities) {
                            val symbolID = version + standardID + symbolSet + status + amplifier + entity + modifiers
                            val position = randomPosition
                            val unitModifiers = mapOf(
                                "W" to getDateTimeGroup(Date()),
                                "Y" to getLocation(position),
                            )
                            symbolLayer.addRenderable(
                                createPlacemark(position, symbolID, unitModifiers, renderAttributes)
                            )
                            numSymbolsCreated++
                        }
                    }
                }
            }
            // Signal a change in the WorldWind scene
            // requestRedraw() is callable from any thread.
            wwd.requestRedraw()

            // Clear the status message set in onPreExecute
            statusText.text = "%,d Symbols Created".format(numSymbolsCreated)
        }// Use a random sin value to generate latitudes without clustering at the poles.

        /**
         * Returns an even distribution of latitude and longitudes across the globe.
         *
         * @return A random latitude/longitude with a zero altitude
         */
        protected val randomPosition: Position get() {
            // Use a random sin value to generate latitudes without clustering at the poles.
            val lat = toDegrees(asin(random.nextDouble())) * if (random.nextBoolean()) 1 else -1
                val lon = 180.0 - random.nextDouble() * 360
                return fromDegrees(lat, lon, 0.0)
            }

        /**
         * Returns a date-time group (DTG) string for the given date.
         *
         * @param date The date/time to be formatted as a date-time group
         *
         * @return DDHHMMSSZMONYYYY
         */
        protected open fun getDateTimeGroup(date: Date) = dateTimeGroup.format(date).uppercase()

        /**
         * Returns a location string for the given position.
         *
         * @param position The position to be formated as location string
         *
         * @return xx.dddddhyyy.dddddh where xx = degrees latitude, yyy = degrees longitude, .ddddd = decimal degrees,
         * and h = direction (N, E, S, W)
         */
        protected open fun getLocation(position: Position): String {
            return "%02.5f%s%03.5f%s".format(
                abs(position.latitude.inDegrees),
                if (position.latitude.inDegrees > 0) "N" else "S",
                abs(position.longitude.inDegrees),
                if (position.longitude.inDegrees > 0) "E" else "W"
            )
        }

        protected open fun createPlacemark(
            position: Position, symbolID: String, unitModifiers: Map<String, String>, renderAttributes: Map<String, String>
        ) = MilStd2525Placemark(symbolID, position, unitModifiers, renderAttributes).apply {
            eyeDistanceScalingThreshold = 15e5
            isEyeDistanceScaling = true
        }
    }

    companion object {
        private val standardIdentities = arrayOf(
            "0",  // Pending
            "1",  // Unknown
            "2",  // Assumed Friend
            "3",  // Friend
            "4",  // Neutral
            "5",  // Suspect
            "6",  // Hostile
        )
        private val symbolSets = arrayOf(
            "00",  // Unknown
            "01",  // Air
            "05",  // Space
            "10",  // Land Unit
            "15",  // Land Equipment
            "20",  // Land Installation
            "30",  // Sea surface
            "35",  // Subsurface
            "36",  // Mine Wafare
        )
        private val statusCodes = arrayOf(
            "0",  // Present
            "1",  // Planned/Anticipated/Suspect
            "2",  // Present/Fully Capable
            "3",  // Present/Damaged
            "4",  // Present/Destroyed
            "5"   // Present/Full to Capacity
        )
        private val unknownEntities = arrayOf("000000")
        private val airEntities = arrayOf(
            "110000", "110100", "110101", "110102", "110103", "110104", "110105", "110107", "110108", "110109",
            "110110", "110111", "110112", "110113", "110114", "110115", "110116", "110117", "110118", "110119",
            "110120", "110121", "110122", "110123", "110124", "110125", "110126", "110127", "110128", "110129",
            "110130", "110131", "110132", "110133", "110200", "110300", "110400", "110500", "110600", "110700",
            "120000", "120100", "120200", "120300", "120400", "120500", "120600", "130000", "130100", "130200",
            "140000",
        )
        private val spaceEntities = arrayOf(
            "110000", "110100", "110200", "110300", "110400", "110500", "110600", "110700", "110800", "110900",
            "111000", "111100", "111200", "111300", "111400", "111500", "111600", "111700", "111800", "111900",
            "120000", "120100", "120200", "120300", "120400", "120500", "120600", "120700", "120800", "120900",
            "121000", "121100", "121200", "130000",
        )
        private val landUnitEntities = arrayOf(
            "110000", "110100", "110200", "110300", "110400", "110500", "110600", "110601", "110700", "110800",
            "110900", "111000", "111001", "111002", "111003", "111004", "111005", "111100", "111200", "120000",
            "120100", "120200", "120300", "120400", "120401", "120402", "120500", "120501", "120502", "120600",
            "120601", "120700", "120800", "120801", "120900", "121000", "121100", "121101", "121102", "121103",
            "121104", "121105", "121200", "121300", "121301", "121302", "121303", "121400", "121500", "121600",
            "121700", "121800", "121801", "121802", "121803", "121804", "121805", "121900", "130000", "130100",
            "130101", "130102", "130200", "130300", "130301", "130302", "130400", "130500", "130600", "130700",
            "130800", "130801", "130802", "130803", "130900", "140000", "140100", "140101", "140102", "140103",
            "140104", "140105", "140200", "140300", "140400", "140500", "140600", "140700", "140701", "140702",
            "140703", "140800", "140900", "141000", "141100", "141200", "141300", "141400", "141500", "141600",
            "141700", "141701", "141702", "141800", "141900", "142000", "142100", "150000", "150100", "150200",
            "150300", "150400", "150500", "150501", "150502", "150503", "150504", "150505", "150600", "150700",
            "150800", "150900", "151000", "151100", "151200", "160000", "160100", "160200", "160300", "160400",
            "160500", "160600", "160700", "160800", "160900", "161000", "161100", "161200", "161300", "161400",
            "161500", "161600", "161700", "161800", "161900", "162000", "162100", "162200", "162300", "162400",
            "162500", "162600", "162700", "162800", "162900", "163000", "163100", "163200", "163300", "163400",
            "163500", "163600", "163700", "163800", "163900", "164000", "164100", "164200", "164300", "164400",
            "164500", "164600", "164700", "164800", "164900", "170000", "170100", "180000", "180100", "180200",
            "180300", "180400", "190000", "200000", "200100", "200200", "200300", "200400", "200500", "200600",
            "200700", "200800", "200900", "201000", "201100", "201200", "201300",
        )
        private val landEquipmentEntities = arrayOf(
            "110000", "110100", "110101", "110102", "110103", "110200", "110201", "110202", "110203", "110300",
            "110301", "110302", "110303", "110400", "110500", "110501", "110502", "110503", "110600", "110601",
            "110602", "110603", "110700", "110701", "110702", "110703", "110800", "110801", "110802", "110803",
            "110900", "110901", "110902", "110903", "111000", "111001", "111002", "111003", "111100", "111101",
            "111102", "111103", "111104", "111105", "111106", "111107", "111108", "111109", "111200", "111201",
            "111202", "111203", "111300", "111301", "111302", "111303", "111400", "111401", "111402", "111403",
            "111500", "111501", "111502", "111503", "111600", "111601", "111602", "111603", "111701", "111701",
            "111702", "111703", "111800", "111900", "112000", "120000", "120100", "120101", "120102", "120103",
            "120104", "120105", "120106", "120107", "120108", "120109", "120110", "120200", "120201", "120202",
            "120203", "120300", "120301", "120302", "120303", "130000", "130100", "130200", "130300", "130400",
            "130500", "130600", "130700", "130701", "130800", "130801", "130900", "130901", "130902", "131000",
            "131001", "131002", "131003", "131100", "131101", "131200", "131300", "131400", "131500", "131600",
            "140000", "140100", "140200", "140300", "140400", "140500", "140600", "140601", "140602", "140603",
            "140700", "140800", "140900", "141000", "141100", "141200", "141201", "141202", "150000", "150100",
            "150200", "160000", "160100", "160101", "160102", "160103", "160200", "160201", "160202", "160203",
            "160300", "160301", "160302", "160303", "160400", "160401", "160402", "160403", "160500", "160501",
            "160502", "160503", "160600", "160601", "160602", "160603", "160700", "160701", "160702", "160703",
            "160800", "160900", "170000", "170100", "170200", "170300", "170400", "170500", "170600", "170700",
            "170800", "170900", "171000", "171100", "180000", "190000", "190100", "190200", "190300", "190400",
            "190500", "200000", "200100", "200200", "200300", "200400", "200500", "200600", "200700", "200800",
            "200900", "201000", "201100", "201200", "201300", "201400", "201500", "201501", "210000", "210100",
            "210200", "210300", "210400", "210500", "220000", "220100", "220200", "220300", "230000", "230100",
            "230200", "240000",
        )
        private val landInstallationEntities = arrayOf(
            "110000", "110100", "110200", "110300", "110400", "110500", "110600", "110700", "110701", "110800",
            "110900", "111000", "111100", "111200", "111300", "111400", "111500", "111600", "111700", "111800",
            "111900", "111901", "111902", "112000", "112100", "112101", "112102", "112103", "112104", "112105",
            "112106", "112107", "112108", "112109", "112110", "112111", "112112", "112200", "112201", "112202",
            "120000", "120100", "120101", "120102", "120103", "120104", "120105", "120106", "120107", "120108",
            "120200", "120201", "120202", "120203", "120204", "120205", "120206", "120207", "120300", "120301",
            "120302", "120303", "120304", "120305", "120306", "120307", "120308", "120309", "120310", "120400",
            "120401", "120402", "120500", "120501", "120502", "120503", "120504", "120505", "120506", "120600",
            "120700", "120701", "120702", "120800", "120801", "120802", "120900", "120901", "120902", "121000",
            "121001", "121002", "121003", "121004", "121100", "121101", "121102", "121103", "121200", "121201",
            "121202", "121203", "121300", "121301", "121302", "121303", "121304", "121305", "121306", "121307",
            "121308", "121309", "121310", "121311", "121312", "121313", "121400", "121401", "121402", "121403",
            "121404", "121405", "121406", "121407", "121408", "121409", "121410", "121411",
        )
        private val seaSurfaceEntities = arrayOf(
            "110000", "120000", "120100", "120200", "120201", "120202", "120203", "120204", "120205", "120206",
            "120300", "120301", "120302", "120303", "120304", "120305", "120306", "120307", "120308", "120400",
            "120401", "120402", "120403", "120404", "120405", "120406", "120500", "120501", "120502", "120600",
            "120700", "120800", "120801", "120900", "121000", "121001", "121002", "121003", "121004", "121005",
            "121100", "130000", "130100", "130101", "130102", "130103", "130104", "130105", "130106", "130107",
            "130108", "130109", "130110", "130111", "130112", "130113", "130200", "130201", "130202", "130203",
            "130204", "140000", "140100", "140101", "140102", "140103", "140104", "140105", "140106", "140107",
            "140108", "140109", "140110", "140111", "140112", "140113", "140114", "140115", "140116", "140200",
            "140201", "140202", "140203", "140300", "140400", "140500", "140501", "140502", "140600", "140700",
            "150000", "160000", "170000",
        )
        private val subsurfaceEntities = arrayOf(
            "110000", "110100", "110101", "110102", "110103", "110200", "110300", "110400", "110500", "120000",
            "120100", "120200", "120300", "130000", "130100", "130200", "130300", "140000", "150000", "160000",
        )
        private val mineWarfareEntities = arrayOf(
            "110000", "110100", "110200", "110300", "110400", "110500", "110600", "110700", "110800", "110801",
            "110802", "110803", "110804", "110900", "110901", "110902", "110903", "110904", "110905", "120000",
            "130000", "130100", "130200", "140000", "140100", "140101", "140102", "140103", "140104", "140105",
            "140200", "140201", "140202", "140203", "140204", "140205", "140300", "140301", "140302", "140303",
            "140304", "140305", "140400", "140401", "140402", "140403", "140404", "140405", "150000", "150100",
            "150200", "150300", "160000", "160100", "160200", "160300", "170000", "170100", "180000", "190000",
            "190100", "190200", "190300", "200000", "210000",
        )
    }
}