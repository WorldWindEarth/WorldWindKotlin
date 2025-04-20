package earth.worldwind.examples

import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.milstd2525.MilStd2525
import earth.worldwind.shape.milstd2525.MilStd2525Placemark
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.random.Random

open class PlacemarksMilStd2525DemoActivity: GeneralGlobeActivity() {
    protected var animationStared = false
    protected var pauseAnimation = false
    protected var frameCount = 0
    // A component for displaying the status of this activity
    protected lateinit var statusText: TextView
    // A collection of aircraft to be animated
    protected val aircraftPositions = mutableMapOf<Placemark, Position>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_placemarks_milstd2525_demo)
        aboutBoxText = """Demonstrates a LOT of MIL-STD-2525 Placemarks.
There are $NUM_AIRPORTS airports and $NUM_AIRCRAFT aircraft symbols in this example."""

        // Add a TextView on top of the globe to convey the status of this activity
        statusText = TextView(this)
        statusText.setTextColor(Color.YELLOW)
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(statusText)

        // Initialize MIL-STD-2525 rendering library and symbols on background threads. Async tasks
        // are executed serially, ensuring the renderer is initialized before we create symbols.
        InitializeRendererTask().execute()
        CreateSymbolsTask().execute()
    }

    override fun onPause() {
        super.onPause()
        // Stop running the animation when this activity is paused.
        pauseAnimation = true
    }

    override fun onResume() {
        super.onResume()
        // Resume aircraft animation
        if (animationStared) {
            pauseAnimation = false
            lifecycleScope.launch {
                delay(DELAY_TIME)
                doAnimation()
            }
        }
    }

    /**
     * Initiates the aircraft animation.
     */
    protected open fun startAnimation() {
        statusText.text = "Starting the animation..."
        animationStared = true
        pauseAnimation = false

        lifecycleScope.launch {
            // Start the animation
            doAnimation()

            // Clear the "Starting..." status text after a few seconds
            delay(3000)
            statusText.text = ""
        }
    }

    /**
     * Animates the aircraft symbols. Each execution of this method is an "animation frame" that updates the aircraft
     * positions on the UI Thread.
     */
    private fun doAnimation() {
        AnimateAircraftTask().execute()
    }

    /**
     * The Airport class ia a simple POD (plain old data) structure representing an airport from VMAP0 data.
     */
    protected open class Airport internal constructor(
        val position: Position,
        val name: String,
        val use: String,
        val country: String
    ) {
        override fun toString() = "Airport{position=$position, name='$name', use='$use', country='$country'}"

        companion object {
            const val MILITARY = "8" //"Military" USE code
            const val CIVILIAN = "49" //"Civilian/Public" USE code;
            const val JOINT = "22" //"Joint Military/Civilian" USE code;
            const val OTHER = "999" //"Other" USE code;
        }
    }

    /**
     * InitializeRendererTask is an async task that initializes the MIL-STD-2525 Rendering Library on a background
     * thread. It must be created and executed on the UI Thread.
     */
    protected open inner class InitializeRendererTask : CoroutinesAsyncTask<Void, Void, Void>() {
        override fun onPreExecute() {
            super.onPreExecute()
            statusText.text = "Initializing the MIL-STD-2525 Library..."
        }

        /**
         * Initialize the MIL-STD-2525 Rendering Library.
         */
        override fun doInBackground(vararg params: Void): Void? {
            // Time consuming . . .
            MilStd2525.initializeRenderer(applicationContext)
            return null
        }

        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)
            statusText.text = ""
        }
    }

    /**
     * CreateSymbolsTask is an async task that initializes the aircraft and airport symbols on a background thread. It
     * must be created and executed on the UI Thread.
     */
    protected open inner class CreateSymbolsTask : CoroutinesAsyncTask<Void, String, Void>() {
        private val airports = mutableListOf<Airport>()
        private val airportLayer = RenderableLayer()
        private val aircraftLayer = RenderableLayer()

        /**
         * Loads the aircraft database and creates the placemarks on a background thread. The [RenderableLayer]
         * objects for the airport and aircraft symbols have not been attached to the WorldWind at this stage, so its
         * safe to perform this operation on a background thread.  The layers will be added to the WorldWindow in
         * onPostExecute.
         */
        override fun doInBackground(vararg params: Void): Void? {
            loadAirportDatabase()
            createAirportSymbols()
            createAircraftSymbols()
            return null
        }

        /**
         * Updates the statusText TextView on the UI Thread.
         *
         * @param values An array of status messages.
         */
        override fun onProgressUpdate(vararg values: String) {
            super.onProgressUpdate(*values)
            statusText.text = values[0]
        }

        /**
         * Updates the WorldWindow layer list on the UI Thread and starts the animation.
         */
        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)
            wwd.engine.layers.apply {
                addLayer(airportLayer)
                addLayer(aircraftLayer)
            }
            statusText.text = ""
            startAnimation()
        }

        /**
         * Loads the VMAP0 world airport data.
         */
        private fun loadAirportDatabase() {
            publishProgress("Loading world airports database...")
            try {
                var headers = true
                var lat = 0
                var lon = 0
                var nam = 0
                var na3 = 0
                var use = 0
                resources.openRawResource(R.raw.world_apts).bufferedReader().forEachLine { line ->
                    val fields = line.split(",")
                    if (headers) {
                        headers = false
                        // The first line is the CSV header: LAT,LON,ALT,NAM,IKO,NA3,USE,USEdesc
                        lat = fields.indexOf("LAT")
                        lon = fields.indexOf("LON")
                        nam = fields.indexOf("NAM")
                        na3 = fields.indexOf("NA3")
                        use = fields.indexOf("USE")
                    } else {
                        // Read the remaining lines
                        val airport = Airport(
                            fromDegrees(fields[lat].toDouble(), fields[lon].toDouble(), 0.0),
                            fields[nam], fields[use], fields[na3].substring(0, 2)
                        )
                        airports.add(airport)
                    }
                }
            } catch (_: IOException) {
                log(Logger.ERROR, "Exception attempting to read Airports database")
            }
        }

        /**
         * Creates airport symbols from the airports collection and adds them to the airports layer.
         */
        private fun createAirportSymbols() {
            publishProgress("Creating airport symbols...")

            // Shared rendering attributes
            val milStdAttributes = mutableMapOf<String, String>()
            val civilianColorAttributes = mapOf("FILLCOLOR" to String.format("%08x", Color.MAGENTA))
            var placemark: Placemark
            for (airport in airports) {
                val unitModifiers = mutableMapOf<String, String>()
                unitModifiers.put("T", airport.name)
                placemark = when {
                    friends.contains(airport.country) -> {
                        when (airport.use) {
                            Airport.MILITARY, Airport.JOINT -> MilStd2525Placemark(
                                "10032000001213010000", airport.position,
                                unitModifiers, milStdAttributes
                            )
                            Airport.CIVILIAN, Airport.OTHER -> MilStd2525Placemark(
                                "10032000001213011100", airport.position,
                                unitModifiers, civilianColorAttributes
                            )
                            else -> MilStd2525Placemark(
                                "10012000001213010000", airport.position,
                                unitModifiers, milStdAttributes
                            )
                        }
                    }
                    neutrals.contains(airport.country) -> {
                        MilStd2525Placemark(
                            "10042000001213010000", airport.position,
                            unitModifiers, milStdAttributes
                        )
                    }
                    hostiles.contains(airport.country) -> {
                        MilStd2525Placemark(
                            "10062000001213010000", airport.position,
                            unitModifiers, milStdAttributes
                        )
                    }
                    else -> {
                        MilStd2525Placemark(
                            "10012000001213010000", airport.position,
                            unitModifiers, milStdAttributes
                        )
                    }
                }

                // Eye scaling is essential for a reasonable display with a high density of airports
                placemark.isEyeDistanceScaling = true
                placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                airportLayer.addRenderable(placemark)
            }
        }

        /**
         * Creates aircraft symbols with randomly assigned origin and destination positions, and adds symbols to the
         * aircraft layer.
         */
        private fun createAircraftSymbols() {
            publishProgress("Creating aircraft symbols...")
            val random = Random(123)
            for (i in 0 until NUM_AIRCRAFT) {
                // Randomly assign departure and arrival airports to each aircraft
                val departure = airports[random.nextInt(NUM_AIRPORTS - 1)]
                val arrival = airports[random.nextInt(NUM_AIRPORTS - 1)]

                // Allocate the end points of the aircraft's flight path.
                val origin = Position(departure.position.latitude, departure.position.longitude, AIRCRAFT_ALT)
                val destination = Position(arrival.position.latitude, arrival.position.longitude, AIRCRAFT_ALT)

                // Create a MIL-STD-2525 symbol based on the departure airport
                val symbolCode = createAircraftSymbolCode(departure.country, departure.use)
                val unitModifiers = mutableMapOf<String, String>()
                unitModifiers.put("H", "ORIG: " + departure.name)
                unitModifiers.put("G", "DEST: " + arrival.name)
                unitModifiers.put("AS", departure.country)
                val placemark = MilStd2525Placemark(symbolCode, origin, unitModifiers, null).apply {
                    isEyeDistanceScaling = true

                    // Store these flight path end points in the user properties for the computation of the flight path
                    // during the animation frame. The animation will move the aircraft along the great circle route
                    // between these two points.
                    putUserProperty("origin", origin)
                    putUserProperty("destination", destination)
                }

                // Add the placemark to the layer that will render it.
                aircraftLayer.addRenderable(placemark)

                // Add the aircraft the collection of aircraft positions to be animated. The position in this HashMap
                // is the current position of the aircraft.  It is computed and updated in-place by the
                // AnimateAircraftTask.doInBackground() method, and subsequently the placemark's position is set
                // to this value in the AnimateAircraftTask.onPostExecute() method.
                aircraftPositions[placemark] = Position()
            }
        }

        /**
         * Generates a Symbol ID for an aircraft originating the given county and departure airport use type.
         *
         * @param country    A country code as defined in the airport's database.
         * @param airportUse The use code for the departure airport.
         *
         * @return A 30-character numeric identifier.
         */
        private fun createAircraftSymbolCode(country: String, airportUse: String): String {
            val identity = when {
                friends.contains(country) -> "3"
                neutrals.contains(country) -> "4"
                hostiles.contains(country) -> "6"
                else -> "1"
            }
            val type = when (airportUse) {
                Airport.MILITARY, Airport.JOINT -> "1101" // Military fixed wing
                Airport.CIVILIAN, Airport.OTHER -> "1201" // Civilian fixed wing
                else -> "0000"
            }

            // Adding the country code the symbol creates more and larger images, but it adds a useful bit
            // of context to the aircraft as they fly across the globe.  Replace country with "**" to reduce
            // the memory footprint of the image textures.
            return "100" + identity + "010000" + type + "000000"
        }
    }

    /**
     * AnimateAircraftTask is an async task that computes and updates the aircraft positions. It must be created and
     * executed on the UI Thread.
     */
    protected inner class AnimateAircraftTask : CoroutinesAsyncTask<Void, Void, Void>() {
        /**
         * Computes the aircraft positions on a background thread.
         */
        override fun doInBackground(vararg params: Void): Void? {
            val amount = frameCount++ / ANIMATION_FRAMES.toDouble() // fractional amount along path
            for (aircraft in aircraftPositions.keys) {
                // Move the aircraft placemark along its great circle flight path.
                val origin = aircraft.getUserProperty<Position>("origin")
                val destination = aircraft.getUserProperty<Position>("destination")
                val currentPosition = aircraftPositions[aircraft]
                if (origin == null || destination == null || currentPosition == null) continue

                // Update the currentPosition members (in-place)
                origin.interpolateAlongPath(destination, PathType.GREAT_CIRCLE, amount, currentPosition)
            }
            return null
        }

        /**
         * Updates the aircraft placemark positions on the UI Thread.
         */
        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)

            // Update the aircraft placemark positions with the positions computed on the background thread.
            for (aircraft in aircraftPositions.keys) aircraft.position = aircraftPositions[aircraft]!!
            wwd.requestRedraw()

            // Determine if the animation is done
            if (frameCount > ANIMATION_FRAMES) {
                // All the aircraft have arrived at their destinations; pause the animation
                pauseAnimation = true
                statusText.text = "Animation complete"
            }

            // Re-execute the animation after the prescribed delay
            if (!pauseAnimation) lifecycleScope.launch {
                delay(DELAY_TIME)
                doAnimation()
            }
        }
    }

    companion object {
        // The delay in milliseconds between aircraft animation frames
        protected const val DELAY_TIME = 100L

        // The number of frames for the aircraft animation
        protected const val ANIMATION_FRAMES = 1000

        // There are 9,809 airports in the world airports database.
        protected const val NUM_AIRPORTS = 9809

        // The number of aircraft in the animation
        protected const val NUM_AIRCRAFT = 5000
        protected const val AIRCRAFT_ALT = 10000.0 // meters

        // NATO countries
        protected val friends = listOf(
            "US", "CA", "UK", "DA", "FR", "IT", "IC", "NL", "NO", "PO", "GR", "TU", "GM",
            "SP", "EZ", "PL", "HU", "SI", "RO", "BU", "LO", "LH", "LG", "EN", "AL", "HR"
        )

        // A few neutral countries
        protected val neutrals = listOf("AU", "MX", "SW", "SZ")

        // A few hostile countries
        protected val hostiles = listOf("RS", "IR")
    }
}