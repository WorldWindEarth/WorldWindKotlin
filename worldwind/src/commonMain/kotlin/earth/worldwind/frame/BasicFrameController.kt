package earth.worldwind.frame

import earth.worldwind.PickedObject.Companion.fromTerrain
import earth.worldwind.PickedObject.Companion.identifierToUniqueColor
import earth.worldwind.PickedObject.Companion.uniqueColorToIdentifier
import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableSurfaceColor
import earth.worldwind.draw.DrawableSurfaceColor.Companion.obtain
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.GL_COLOR_BUFFER_BIT
import earth.worldwind.util.kgl.GL_DEPTH_BUFFER_BIT
import kotlin.math.roundToInt

open class BasicFrameController: FrameController {
    private var pickColor = Color()
    private val pickPoint = Vec3()
    private val pickPos = Position()

    override fun renderFrame(rc: RenderContext) {
        rc.terrainTessellator!!.tessellate(rc)
        if (rc.isPickMode) renderTerrainPickedObject(rc)
        rc.layers!!.render(rc)
        rc.sortDrawables()
    }

    protected open fun renderTerrainPickedObject(rc: RenderContext) {
        if (rc.terrain!!.sector.isEmpty) return  // no terrain to pick

        // Acquire a unique picked object ID for terrain.
        val pickedObjectId = rc.nextPickedObjectId()

        // Enqueue a drawable for processing on the OpenGL thread that displays terrain in the unique pick color.
        val pool = rc.getDrawablePool<DrawableSurfaceColor>()
        val drawable = obtain(pool)
        identifierToUniqueColor(pickedObjectId, drawable.color)
        drawable.program = rc.getShaderProgram { BasicShaderProgram() }
        rc.offerSurfaceDrawable(drawable, Double.NEGATIVE_INFINITY)

        // If the pick ray intersects the terrain, enqueue a picked object that associates the terrain drawable with its
        // picked object ID and the intersection position.
        val pickRay = rc.pickRay
        if (pickRay != null && rc.terrain!!.intersect(pickRay, pickPoint)) {
            rc.globe!!.cartesianToGeographic(pickPoint.x, pickPoint.y, pickPoint.z, pickPos)
            rc.offerPickedObject(fromTerrain(pickedObjectId, pickPos))
        }
    }

    override fun drawFrame(dc: DrawContext) {
        clearFrame(dc)
        drawDrawables(dc)
        if (dc.isPickMode) resolvePick(dc)
    }

    protected open fun clearFrame(dc: DrawContext) {
        dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    protected open fun drawDrawables(dc: DrawContext) {
        dc.rewindDrawables()
        while (true) {
            val next = dc.pollDrawable() ?: break
            try {
                next.draw(dc)
            } catch (e: Exception) {
                logMessage(
                    ERROR, "BasicFrameController", "drawDrawables",
                    "Exception while drawing '$next'", e
                )
                // Keep going. Draw the remaining drawables.
            }
        }
    }

    protected open fun resolvePick(dc: DrawContext) {
        val pickedObjects = dc.pickedObjects ?: return
        if (pickedObjects.count == 0) return  // no eligible objects; avoid expensive calls to glReadPixels
        val pickViewport = dc.pickViewport ?: return
        val pickPointOnly = dc.pickPoint != null && pickViewport.width <= 3 && pickViewport.height <= 3
        var objectFound = false

        dc.pickPoint?.let { pickPoint ->
            // Read the fragment color at the pick point.
            dc.readPixelColor(pickPoint.x.roundToInt(), pickPoint.y.roundToInt(), pickColor)

            // Convert the fragment color to a picked object ID. It returns zero if the color cannot indicate a picked
            // object ID, in which case no objects have been drawn at the pick point.
            val topObjectId = uniqueColorToIdentifier(pickColor)
            if (topObjectId != 0) {
                val terrainObject = pickedObjects.terrainPickedObject
                val topObject = pickedObjects.pickedObjectWithId(topObjectId)
                if (topObject != null) {
                    topObject.markOnTop()
                    if (!topObject.isTerrain) objectFound = true // Non-terrain object found in pick point
                    // Remove picked objects except top and terrain in case of object found or point only mode
                    // Using clearPickedObjects and two offerPickedObject is faster than keepTopAndTerrainObjects at the end
                    if (pickPointOnly || objectFound) {
                        pickedObjects.clearPickedObjects()
                        pickedObjects.offerPickedObject(topObject)
                        // handles null objects and duplicate objects
                        if (terrainObject != null) pickedObjects.offerPickedObject(terrainObject)
                    }
                } else if (pickPointOnly) pickedObjects.clearPickedObjects() // no eligible objects drawn at the pick point
            } else if (pickPointOnly) pickedObjects.clearPickedObjects() // no objects drawn at the pick point
        }

        if (!pickPointOnly && !objectFound) {
            // Read the unique fragment colors in the pick rectangle.
            dc.readPixelColors(pickViewport.x, pickViewport.y, pickViewport.width, pickViewport.height).forEach { pickColor ->
                // Convert the fragment color to a picked object ID. This returns zero if the color cannot indicate a picked
                // object ID.
                val topObjectId = uniqueColorToIdentifier(pickColor)
                if (topObjectId != 0) {
                    val topObject = pickedObjects.pickedObjectWithId(topObjectId)
                    if (topObject != null && !topObject.isTerrain) topObject.markOnTop()
                }
            }

            // Remove all picked objects not marked as on top or terrain.
            pickedObjects.keepTopAndTerrainObjects()
        }
    }
}