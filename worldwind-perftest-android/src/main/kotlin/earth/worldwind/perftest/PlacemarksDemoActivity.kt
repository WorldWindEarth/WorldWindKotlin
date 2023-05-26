package earth.worldwind.perftest

import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Placemark.LevelOfDetailSelector
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * This Activity demonstrates a LOT of Placemarks with varying levels of detail.
 */
open class PlacemarksDemoActivity: GeneralGlobeActivity() {
    // A component for displaying the status of this activity
    protected lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_placemarks_demo)
        aboutBoxText = """Demonstrates LOTS (38K) of Placemarks with various levels of detail.

Placemarks are conditionally displayed based on the camera distance: 
 - symbols are based on population and capitol status,
 - zoom in to reveal more placemarks,
 - picking is supported, touch a placemark to see the place name."""

        // Add a TextView on top of the globe to convey the status of this activity
        statusText = TextView(this)
        statusText.setTextColor(Color.YELLOW)
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(statusText)

        // Override the WorldWindow's built-in navigation behavior by adding picking support.
        wwd.controller = PickController(wwd)

        // Set the camera to look at the area where the symbols will be displayed.
        val pos = fromDegrees(38.0, -98.0, wwd.engine.distanceToViewGlobeExtents)
        wwd.engine.camera.position.copy(pos)
        CreatePlacesTask().execute()
    }

    /**
     * The PlaceLevelOfDetailSelector dynamically sets the PlacemarkAttributes for a Placemark instance. An instance of
     * this class, configured with a Place object, is added to the placemark representing the Place object. The
     * placemark's attribute bundle is selected based on the place's population and its status as a capital.  The
     * placemark's visibility is based on its distance to the camera.
     *
     * @param resources The application resources
     * @param place     The place for which
     */
    protected open class PlaceLevelOfDetailSelector(protected val resources: Resources, protected val place: Place): LevelOfDetailSelector {
        protected var lastLevelOfDetail = -1
        protected var lastHighlightState = false
        protected var attributes: PlacemarkAttributes? = null

        /**
         * Gets the active attributes for the current distance to the camera and highlighted state.
         *
         * @param rc             Rendering context
         * @param placemark      The placemark needing a level of detail selection
         * @param cameraDistance The distance from the placemark to the camera (meters)
         *
         * @return if placemark should display or skip its rendering
         */
        override fun selectLevelOfDetail(
            rc: RenderContext, placemark: Placemark, cameraDistance: Double
        ): Boolean {
            // Determine the attributes based on the distance from the camera to the placemark
            val highlighted = placemark.isHighlighted
            val highlightChanged = lastHighlightState != highlighted
            if (cameraDistance > LEVEL_0_DISTANCE) {
                if (lastLevelOfDetail != LEVEL_0 || highlightChanged) {
                    attributes = if (place.population > LEVEL_0_POPULATION || place.isCapital)
                        getPlacemarkAttributes(resources, place) else null
                    lastLevelOfDetail = LEVEL_0
                }
            } else if (cameraDistance > LEVEL_1_DISTANCE) {
                if (lastLevelOfDetail != LEVEL_1 || highlightChanged) {
                    attributes = if (place.population > LEVEL_1_POPULATION || place.isCapital)
                        getPlacemarkAttributes(resources, place) else null
                    lastLevelOfDetail = LEVEL_1
                }
            } else if (cameraDistance > LEVEL_2_DISTANCE) {
                if (lastLevelOfDetail != LEVEL_2 || highlightChanged) {
                    attributes = if (place.population > LEVEL_2_POPULATION || place.isCapital)
                        getPlacemarkAttributes(resources, place) else null
                    lastLevelOfDetail = LEVEL_2
                }
            } else if (cameraDistance > LEVEL_3_DISTANCE) {
                if (lastLevelOfDetail != LEVEL_3 || highlightChanged) {
                    attributes = if (place.population > LEVEL_3_POPULATION || place.isCapital)
                        getPlacemarkAttributes(resources, place) else null
                    lastLevelOfDetail = LEVEL_3
                }
            } else if (cameraDistance > LEVEL_4_DISTANCE) {
                if (lastLevelOfDetail != LEVEL_4 || highlightChanged) {
                    attributes = if (place.population > LEVEL_4_POPULATION || place.isCapital)
                        getPlacemarkAttributes(resources, place) else null
                    lastLevelOfDetail = LEVEL_4
                }
            } else {
                if (lastLevelOfDetail != LEVEL_5 || highlightChanged) {
                    attributes = getPlacemarkAttributes(resources, place)
                    lastLevelOfDetail = LEVEL_5
                }
            }
            if (highlightChanged) {
                // Use a distinct set attributes when highlighted, otherwise used the shared attributes
                if (highlighted) {
                    // Create a copy of the shared attributes bundle and increase the scale
                    attributes = attributes?.let { PlacemarkAttributes(it).apply { imageScale *= 2.0 } }
                }
            }
            lastHighlightState = highlighted

            // Update the placemark's attributes bundle
            return attributes?.let {
                it.isDrawLabel = highlighted || cameraDistance <= LEVEL_1_DISTANCE
                placemark.attributes = it
                true // Placemark visible
            } ?: false // Placemark invisible
        }

        companion object {
            internal const val LEVEL_0_DISTANCE = 2000000.0
            internal const val LEVEL_0_POPULATION = 500000.0
            internal const val LEVEL_1_DISTANCE = 1500000.0
            internal const val LEVEL_1_POPULATION = 250000.0
            internal const val LEVEL_2_DISTANCE = 500000.0
            internal const val LEVEL_2_POPULATION = 100000.0
            internal const val LEVEL_3_DISTANCE = 250000.0
            internal const val LEVEL_3_POPULATION = 50000.0
            internal const val LEVEL_4_DISTANCE = 100000.0
            internal const val LEVEL_4_POPULATION = 10000.0
            internal const val LEVEL_0 = 0
            internal const val LEVEL_1 = 1
            internal const val LEVEL_2 = 2
            internal const val LEVEL_3 = 3
            internal const val LEVEL_4 = 4
            internal const val LEVEL_5 = 5
            protected val iconCache = mutableMapOf<String, WeakReference<PlacemarkAttributes>>()

            protected fun getPlacemarkAttributes(resources: Resources, place: Place): PlacemarkAttributes {
                var resourceId: Int
                var scale: Double
                when {
                    place.population > LEVEL_0_POPULATION -> {
                        resourceId = R.drawable.btn_rating_star_on_selected
                        scale = 1.3
                    }
                    place.population > LEVEL_1_POPULATION -> {
                        resourceId = R.drawable.btn_rating_star_on_pressed
                        scale = 1.2
                    }
                    place.population > LEVEL_2_POPULATION -> {
                        resourceId = R.drawable.btn_rating_star_on_normal
                        scale = 1.1
                    }
                    place.population > LEVEL_3_POPULATION -> {
                        resourceId = R.drawable.btn_rating_star_off_selected
                        scale = 0.7
                    }
                    place.population > LEVEL_4_POPULATION -> {
                        resourceId = R.drawable.btn_rating_star_off_pressed
                        scale = 0.6
                    }
                    else -> {
                        resourceId = R.drawable.btn_rating_star_off_normal
                        scale = 0.6
                    }
                }
                when (place.type) {
                    Place.NATIONAL_CAPITAL -> {
                        resourceId = R.drawable.star_big_on
                        scale *= 2.5
                    }
                    Place.STATE_CAPITAL -> {
                        resourceId = R.drawable.star_big_on
                        scale *= 1.79
                    }
                }

                // Generate a cache key for this symbol
                val iconKey = "$resources-$resourceId-$scale"

                // Look for an attribute bundle in our cache and determine if the cached reference is valid
                val reference = iconCache[iconKey]
                var placemarkAttributes = reference?.get()

                // Create the attributes if they haven't been created yet or if they've been released
                if (placemarkAttributes == null) {
                    // Create the attributes bundle and add it to the cache.
                    // The actual bitmap will be lazily (re)created using a factory.
                    placemarkAttributes = createPlacemarkAttributes(resourceId, scale)
                    // Add a weak reference to the attribute bundle to our cache
                    iconCache[iconKey] = WeakReference(placemarkAttributes)
                }
                return placemarkAttributes
            }

            protected fun createPlacemarkAttributes(@DrawableRes resourceId: Int, scale: Double): PlacemarkAttributes {
                val placemarkAttributes = PlacemarkAttributes()
                placemarkAttributes.imageSource = fromResource(resourceId)
                placemarkAttributes.imageScale = scale
                placemarkAttributes.minimumImageScale = 0.5
                placemarkAttributes.labelAttributes.textOffset =
                    Offset(OffsetMode.PIXELS, -24.0, OffsetMode.FRACTION, 0.0)
                return placemarkAttributes
            }
        }
    }

    /**
     * The Place class ia a simple POD (plain old data) structure representing an airport from NTAD place data.
     */
    protected open class Place internal constructor(
        val position: Position, val name: String, feature2: String, val population: Int
    ) {
        // FEATURE2 may contain  multiple types; "-999" is used for a regular populated place
        // Here we extract the most important type
        val type = when {
            feature2.contains(NATIONAL_CAPITAL) -> NATIONAL_CAPITAL
            feature2.contains(STATE_CAPITAL) -> STATE_CAPITAL
            feature2.contains(COUNTY_SEAT) -> COUNTY_SEAT
            else -> PLACE
        }
        val isCapital get() = type == NATIONAL_CAPITAL || type == STATE_CAPITAL

        override fun toString() = "Place{name='$name', position=$position, type='$type', population=$population}"

        companion object {
            const val PLACE = "Populated Place"
            const val COUNTY_SEAT = "County Seat"
            const val STATE_CAPITAL = "State Capital"
            const val NATIONAL_CAPITAL = "National Capital"
        }
    }

    /**
     * This inner class is a custom WorldWindController that handles both picking and navigation via a combination of
     * the native WorldWind navigation gestures and Android gestures. This class' onTouchEvent method arbitrates
     * between pick events and globe navigation events.
     */
    open inner class PickController(wwd: WorldWindow): BasicWorldWindowController(wwd) {
        protected lateinit var pickRequest: Deferred<PickedObjectList> // last picked objects from onDown event
        protected var selectedObject: Any? = null // last "selected" object from single tap

        /**
         * Assign a subclassed SimpleOnGestureListener to a GestureDetector to handle the "pick" events.
         */
        protected open val pickGestureDetector = GestureDetector(applicationContext, object : SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                pick(event) // Pick the object(s) at the tap location
                return false // By not consuming this event, we allow it to pass on to the navigation gesture handlers
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleSelection() // Highlight the picked object

                // By not consuming this event, we allow the "up" event to pass on to the navigation gestures,
                // which is required for proper zoom gestures.  Consuming this event will cause the first zoom
                // gesture to be ignored.  As an alternative, you can implement onSingleTapConfirmed and consume
                // event as you would expect, with the trade-off being a slight delay tap response.
                return false
            }
        })

        /**
         * Delegates events to the pick handler or the native WorldWind navigation handlers.
         */
        override fun onTouchEvent(event: MotionEvent) =
            // Allow pick listener to process the event first.
            // If event was not consumed by the pick operation, pass it on the globe navigation handlers
            // The super class performs the pan, tilt, rotate and zoom
            if (!pickGestureDetector.onTouchEvent(event)) super.onTouchEvent(event) else true

        /**
         * Performs a pick at the tap location.
         */
        fun pick(event: MotionEvent) { pickRequest = wwd.pickAsync(event.x, event.y) }

        /**
         * Toggles the selected state of a picked object.
         */
        fun toggleSelection() {
            wwd.mainScope.launch {
                // Perform a new pick at the screen x, y
                val pickList = pickRequest.await()

                // Get the top-most object for our new picked object
                val topPickedObject = pickList.topPickedObject
                val pickedObject = topPickedObject?.userObject

                // Display the highlight or normal attributes to indicate the
                // selected or unselected state respectively.
                if (pickedObject is Highlightable) {
                    // Determine if we've picked a "new" object so we know to deselect the previous selection
                    val isNewSelection = pickedObject !== selectedObject

                    // Only one object can be selected at time; deselect any previously selected object
                    if (isNewSelection && selectedObject is Highlightable) (selectedObject as Highlightable).isHighlighted = false

                    // Show the selection by showing its highlight attributes and enunciating the name
                    if (isNewSelection && pickedObject is Renderable) {
                        Toast.makeText(
                            applicationContext, (pickedObject as Renderable).displayName, Toast.LENGTH_SHORT
                        ).show()
                    }
                    pickedObject.isHighlighted = isNewSelection
                    wwd.requestRedraw()

                    // Track the selected object
                    selectedObject = if (isNewSelection) pickedObject else null
                }
            }
        }
    }

    /**
     * CreatePlacesTask is an async task that initializes the place icons on a background thread. It must be created and
     * executed on the UI Thread.
     */
    protected inner class CreatePlacesTask: CoroutinesAsyncTask<Void, String, Void>() {
        private val places = mutableListOf<Place>()
        private val placeLayer = RenderableLayer()
        private var numPlacesCreated = 0

        /**
         * Loads the ntad_place database and creates the placemarks on a background thread. The [RenderableLayer]
         * objects for the place icons have not been attached to the WorldWind at this stage, so its safe to perform
         * this operation on a background thread.  The layers will be added to the WorldWindow in onPostExecute.
         */
        override fun doInBackground(vararg params: Void): Void? {
            loadPlacesDatabase()
            createPlaceIcons()
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
         * Updates the WorldWindow layer list on the UI Thread.
         */
        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)
            wwd.engine.layers.addLayer(placeLayer)
            statusText.text = "%,d US places created".format(numPlacesCreated)
            wwd.requestRedraw()
        }

        /**
         * Called by doInBackground(); loads the National Transportation Atlas Database (NTAD) place data.
         */
        private fun loadPlacesDatabase() {
            publishProgress("Loading NTAD place database...")
            try {
                var headers = true
                var lat = 0
                var lon = 0
                var nam = 0
                var pop = 0
                var typ = 0
                resources.openRawResource(R.raw.ntad_place).bufferedReader().forEachLine { line ->
                    val fields = line.split(",")
                    if (headers) {
                        headers = false
                        // Process the header in the first line of the CSV file ...
                        lat = fields.indexOf("LATITUDE")
                        lon = fields.indexOf("LONGITUDE")
                        nam = fields.indexOf("NAME")
                        pop = fields.indexOf("POP_2010")
                        typ = fields.indexOf("FEATURE2")
                    } else {
                        // ... and process the remaining lines in the CSV
                        val place = Place(
                            fromDegrees(fields[lat].toDouble(), fields[lon].toDouble(), 0.0),
                            fields[nam],
                            fields[typ], fields[pop].toInt()
                        )
                        places.add(place)
                    }
                }
            } catch (e: IOException) {
                log(Logger.ERROR, "Exception attempting to read NTAD Place database")
            }
        }

        /**
         * Called by doInBackground(); creates place icons from places collection and adds them to the places layer.
         */
        private fun createPlaceIcons() {
            publishProgress("Creating place icons...")
            for (place in places) {
                // Create and configure a Placemark for this place, using a PlaceLevelOfDetailSelector to
                // dynamically set the PlacemarkAttributes.
                val placemark = Placemark(place.position, PlacemarkAttributes(), place.name)
                placemark.levelOfDetailSelector = PlaceLevelOfDetailSelector(resources, place)
                placemark.isEyeDistanceScaling = true
                placemark.eyeDistanceScalingThreshold = PlaceLevelOfDetailSelector.LEVEL_1_DISTANCE
                placemark.label = place.name
                placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND

                // On a background thread, we can add Placemarks to a RenderableLayer that is
                // NOT attached to the WorldWindow. If the layer was attached to the WorldWindow
                // then we'd have to do this on the UI thread.  Later, we'll add the layer to
                // WorldWindow on the UI thread in the onPostExecute() method.
                placeLayer.addRenderable(placemark)
                numPlacesCreated++
            }
        }
    }
}