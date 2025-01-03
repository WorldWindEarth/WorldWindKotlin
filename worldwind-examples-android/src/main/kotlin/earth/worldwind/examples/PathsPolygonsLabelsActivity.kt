package earth.worldwind.examples

import android.graphics.Typeface
import android.os.Bundle
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Offset.Companion.bottomRight
import earth.worldwind.geom.Offset.Companion.center
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.Renderable
import earth.worldwind.shape.*
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.random.Random

/**
 * This activity demonstrates rendering Labels, Paths and Polygons on the globe. All of the Renderable objects are
 * loaded from .csv files via an async task. The CreateRenderablesTask creates the individual Renderable objects on a
 * background thread and adds each renderable to the WorldWindow on the UI Thread (essential!) via publishProgress and
 * onProgressUpdate.
 *
 *
 * This activity also implements a PickController that displays the name of the picked renderable.
 */
open class PathsPolygonsLabelsActivity: GeneralGlobeActivity() {
    // A component for displaying the status of this activity
    protected lateinit var statusText: TextView
    protected val shapesLayer = RenderableLayer("Shapes")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_paths_and_polygons)
        aboutBoxText = """
    Demonstrates place names rendered as labels, world highways rendered as paths and countries rendered as polygons with random interior colors. 
    
    The name of the object(s) are displayed when picked.
    """.trimIndent()
        // Add a TextView on top of the globe to convey the status of this activity
        statusText = TextView(this)
        statusText.setTextColor(android.graphics.Color.YELLOW)
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(statusText)

        // Override the WorldWindow's built-in navigation behavior by adding picking support.
        wwd.controller = PickController(wwd)
        wwd.engine.layers.addLayer(shapesLayer)

        // Load the shapes into the renderable layer
        statusText.text = "Loading countries...."
        CreateRenderablesTask().execute()
    }

    /**
     * CreateRenderablesTask is an async task that initializes the shapes on a background thread. The task must be
     * created and executed on the UI Thread.
     */
    protected open inner class CreateRenderablesTask: CoroutinesAsyncTask<Void, Renderable, Void>() {
        private var numCountriesCreated = 0
        private var numHighwaysCreated = 0
        private var numPlacesCreated = 0
        private val random = Random(22) // Seed the random number generator for random color fills.

        /**
         * Loads the world_highways and world_political_areas files a background thread. The [Renderable] objects
         * are added to the RenderableLayer on the UI thread via onProgressUpdate.
         */
        override fun doInBackground(vararg params: Void): Void? {
            loadCountriesFile()
            loadHighways()
            loadPlaceNames()
            return null
        }

        /**
         * Updates the RenderableLayer on the UI Thread. Invoked by calls to publishProgress.
         *
         * @param values An array of Renderables (length = 1) to add to the shapes layer.
         */
        override fun onProgressUpdate(vararg values: Renderable) {
            super.onProgressUpdate(*values)
            val shape = values[0]
            statusText.text = "Added ${shape.displayName} feature..."
            shapesLayer.addRenderable(shape)
            wwd.requestRedraw()
        }

        /**
         * Updates the status overlay after the background processing is complete.
         */
        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)
            statusText.text = "%,d places, %,d highways and %,d countries created".format(
                numPlacesCreated,
                numHighwaysCreated,
                numCountriesCreated
            )
            wwd.requestRedraw()
        }

        /**
         * Creates Label objects from the VMAP0 World Place Names data. Called by doInBackground().
         */
        private fun loadPlaceNames() {
            // Define the text attributes used for places
            val placeAttrs = TextAttributes()
            placeAttrs.font = Font(28f, Typeface.DEFAULT_BOLD) // Override the normal Typeface default size is 24
            placeAttrs.textOffset = bottomRight() // anchor the label's bottom-right corner at its position

            // Define the text attribute used for lakes
            val lakeAttrs = TextAttributes()
            lakeAttrs.font = Font(32f, Typeface.create("serif", Typeface.BOLD_ITALIC)) // default size is 24
            lakeAttrs.textColor = Color(0f, 1f, 1f, 0.70f) // cyan, with 7% opacity
            lakeAttrs.textOffset = center() // center the label over its position

            // Load the place names
            try {
                var headers = true
                var lat = 0
                var lon = 0
                var nam = 0
                resources.openRawResource(R.raw.world_placenames).bufferedReader().forEachLine { line ->
                    val fields = line.split(",")
                    if (headers) {
                        headers = false
                        // Process the header in the first line of the CSV file ...
                        lat = fields.indexOf("LAT")
                        lon = fields.indexOf("LON")
                        nam = fields.indexOf("PLACE_NAME")
                    } else {
                        // ... and process the remaining lines in the CSV
                        val label = Label(
                            fromDegrees(fields[lat].toDouble(), fields[lon].toDouble(), 0.0),
                            fields[nam], if (fields[nam].contains("Lake")) lakeAttrs else placeAttrs
                        )
                        label.displayName = label.text

                        // Add the Label object to the RenderableLayer on the UI Thread (see onProgressUpdate)
                        publishProgress(label)
                        numPlacesCreated++
                    }
                }
            } catch (e: IOException) {
                log(Logger.ERROR, "Exception attempting to read/parse world_placenames file.")
            }
        }

        /**
         * Creates Path objects from the VMAP0 World Highways data. Called by doInBackground().
         */
        private fun loadHighways() {
            // Define the normal shape attributes
            val attrs = ShapeAttributes()
            attrs.outlineColor.set(1.0f, 1.0f, 0.0f, 1.0f)
            attrs.outlineWidth = 3f

            // Define the shape attributes used for highlighted "highways"
            val highlightAttrs = ShapeAttributes()
            highlightAttrs.outlineColor.set(1.0f, 0.0f, 0.0f, 1.0f)
            highlightAttrs.outlineWidth = 7f

            // Load the highways
            try {
                var headers = true
                // var wkt = 0
                // var hwy = 0
                val wktStart = "\"LINESTRING ("
                val wktEnd = ")\""
                resources.openRawResource(R.raw.world_highways).bufferedReader().forEachLine { line ->
                    if (headers) {
                        // Process the header in the first line of the CSV file ...
                        headers = false
                        // fields = line.split(",")
                        // wkt = fields.indexOf("WKT")
                        // hwy = fields.indexOf("Highway")
                    } else {
                        // ... and process the remaining lines in the CSV
                        // Extract the "well known text"  feature and the attributes
                        // e.g.: "LINESTRING (x.xxx y.yyy,x.xxx y.yyy)",text
                        val featureBegin = line.indexOf(wktStart) + wktStart.length
                        val featureEnd = line.indexOf(wktEnd, featureBegin)
                        val feature = line.substring(featureBegin, featureEnd)
                        val attributes = line.substring(featureEnd + wktEnd.length + 1)

                        // Buildup the Path. Coordinate tuples are separated by ",".
                        val positions = mutableListOf<Position>()
                        val tuples = feature.split(",")
                        for (tuple in tuples) {
                            // The XY tuple components a separated by a space
                            val xy = tuple.split(" ")
                            positions.add(fromDegrees(xy[1].toDouble(), xy[0].toDouble(), 0.0))
                        }
                        val path = Path(positions, attrs)
                        path.highlightAttributes = highlightAttrs
                        path.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                        path.pathType = PathType.LINEAR
                        path.isFollowTerrain = true // essential for preventing long segments from intercepting ellipsoid.
                        path.displayName = attributes

                        // Add the Path object to the RenderableLayer on the UI Thread (see onProgressUpdate)
                        publishProgress(path)
                        numHighwaysCreated++
                    }
                }
            } catch (e: IOException) {
                log(Logger.ERROR, "Exception attempting to read/parse world_highways file.")
            }
        }

        /**
         * Creates Polygon objects from the e VMAP0 World Political Areas data. Called by doInBackground().
         */
        private fun loadCountriesFile() {
            // Define the normal shape attributes
            val commonAttrs = ShapeAttributes()
            commonAttrs.interiorColor.set(1.0f, 1.0f, 0.0f, 0.5f)
            commonAttrs.outlineColor.set(0.0f, 0.0f, 0.0f, 1.0f)
            commonAttrs.outlineWidth = 3f

            // Define the shape attributes used for highlighted countries
            val highlightAttrs = ShapeAttributes()
            highlightAttrs.interiorColor.set(1.0f, 1.0f, 1.0f, 0.5f)
            highlightAttrs.outlineColor.set(1.0f, 1.0f, 1.0f, 1.0f)
            highlightAttrs.outlineWidth = 5f

            // Load the countries
            try {
                var headers = true
                // var geometry = 0
                // var name = 0
                val wktStart = "\"POLYGON ("
                val wktEnd = ")\""
                resources.openRawResource(R.raw.world_political_boundaries).bufferedReader().forEachLine { line ->
                    if (headers) {
                        // Process the header in the first line of the CSV file ...
                        headers = false
                        // fields = line.split(",")
                        // geometry = fields.indexOf("WKT")
                        // name = fields.indexOf("COUNTRY_NA")
                    } else {
                        // ... and process the remaining lines in the CSV
                        // Extract the "well known text" feature and the attributes
                        // e.g.: "POLYGON ((x.xxx y.yyy,x.xxx y.yyy), (x.xxx y.yyy,x.xxx y.yyy))",text,more text,...
                        val featureBegin = line.indexOf(wktStart) + wktStart.length
                        val featureEnd = line.indexOf(wktEnd, featureBegin) + wktEnd.length
                        val feature = line.substring(featureBegin, featureEnd)
                        val fields = line.substring(featureEnd + 1).split(",")
                        val polygon = Polygon().apply {
                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                            pathType = PathType.LINEAR
                            isFollowTerrain = true // essential for preventing long segments from intercepting ellipsoid.
                            displayName = fields[1]
                            attributes = ShapeAttributes(commonAttrs).apply {
                                interiorColor = Color(
                                    red = random.nextFloat(),
                                    green = random.nextFloat(),
                                    blue = random.nextFloat(),
                                    alpha = 0.3f
                                )
                            }
                            highlightAttributes = highlightAttrs
                        }

                        // Process all the polygons within this feature by creating "boundaries" for each.
                        // Individual polygons are bounded by "(" and ")"
                        var polyStart = feature.indexOf("(")
                        while (polyStart >= 0) {
                            val polyEnd = feature.indexOf(")", polyStart)
                            val poly = feature.substring(polyStart + 1, polyEnd)

                            // Buildup the Polygon boundaries. Coordinate tuples are separated by ",".
                            val positions = mutableListOf<Position>()
                            val tuples = poly.split(",")
                            for (tuple in tuples) {
                                // The XY tuple components a separated by a space
                                val xy = tuple.split(" ")
                                positions.add(fromDegrees(xy[1].toDouble(), xy[0].toDouble(), 0.0))
                            }
                            polygon.addBoundary(positions)

                            // Locate the next polygon in the feature
                            polyStart = feature.indexOf("(", polyEnd)
                        }

                        // Add the Polygon object to the RenderableLayer on the UI Thread (see onProgressUpdate).
                        publishProgress(polygon)
                        numCountriesCreated++
                    }
                }
            } catch (e: IOException) {
                log(Logger.ERROR, "Exception attempting to read/parse world_highways file.")
            }
        }
    }

    /**
     * This inner class is a custom WorldWindController that handles both picking and navigation via a combination of
     * the native WorldWind navigation gestures and Android gestures. This class' onTouchEvent method arbitrates
     * between pick events and globe navigation events.
     */
    open inner class PickController(wwd: WorldWindow) : BasicWorldWindowController(wwd) {
        protected val pickedObjects = mutableListOf<Any>() // last picked objects from onDown events

        /**
         * Assign a subclassed SimpleOnGestureListener to a GestureDetector to handle the "pick" events.
         */
        protected open val pickGestureDetector = GestureDetector(applicationContext, object : SimpleOnGestureListener() {
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                pick(event) // Pick the object(s) at the tap location
                return true
            }
        })

        /**
         * Delegates events to the pick handler or the native WorldWind navigation handlers.
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            var consumed = super.onTouchEvent(event)
            if (!consumed) consumed = pickGestureDetector.onTouchEvent(event)
            return consumed
        }

        /**
         * Performs a pick at the tap location.
         */
        fun pick(event: MotionEvent) {
            val pickRegionSize = 40f // pixels

            // Perform a new pick at the screen x, y
            val pickRequest = wwd.pickAsync(
                event.x - pickRegionSize / 2f, event.y - pickRegionSize / 2f, pickRegionSize, pickRegionSize, false
            )
            wwd.mainScope.launch {
                val pickList = pickRequest.await()

                // Forget our last picked objects
                togglePickedObjectHighlights()
                pickedObjects.clear()

                // pickShapesInRect can return multiple objects, i.e., they're may be more that one 'top object'
                // So we iterate through the list instead of calling pickList.topPickedObject which returns the
                // arbitrary 'first' top object.
                for (pickedObject in pickList.objects) if (pickedObject.isOnTop) pickedObjects.add(pickedObject.userObject)
                togglePickedObjectHighlights()
            }
        }

        /**
         * Toggles the highlighted state of a picked object.
         */
        private fun togglePickedObjectHighlights() {
            val message = StringBuilder()
            for (pickedObject in pickedObjects) {
                if (pickedObject is Highlightable) {
                    pickedObject.isHighlighted = !pickedObject.isHighlighted
                    if (pickedObject.isHighlighted) {
                        if (message.isNotEmpty()) message.append(", ")
                        message.append((pickedObject as Renderable).displayName)
                    }
                }
            }
            if (message.isNotEmpty()) Toast.makeText(applicationContext, message.toString(), Toast.LENGTH_SHORT).show()
            wwd.requestRedraw()
        }
    }
}