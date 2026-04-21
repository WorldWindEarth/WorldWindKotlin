package earth.worldwind.frame

import earth.worldwind.PickedObject.Companion.fromTerrain
import earth.worldwind.PickedObject.Companion.identifierToUniqueColor
import earth.worldwind.PickedObject.Companion.uniqueColorToIdentifier
import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableSurfaceColor
import earth.worldwind.draw.DrawableSurfaceColor.Companion.obtain
import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.Terrain
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.GL_COLOR_BUFFER_BIT
import earth.worldwind.util.kgl.GL_DEPTH_ATTACHMENT
import earth.worldwind.util.kgl.GL_DEPTH_BUFFER_BIT
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import earth.worldwind.util.kgl.KglFramebuffer
import kotlin.math.roundToInt

open class BasicFrameController: FrameController {
    override val lastTerrains = mutableMapOf<Globe.Offset, Terrain>()
    private val pickColor = Color()
    private val pickPoint = Vec3()
    private val pickPos = Position()
    private val boundingBox = BoundingBox()
    private val fullSphere = Sector().setFullSphere()
    private val scratchPoint = Vec3()
    private val scratchRay = Line()

    override fun renderFrame(rc: RenderContext) {
        if (!rc.isPickMode) lastTerrains.clear()
        if (rc.globe.is2D && rc.globe.isContinuous) {
            // Tessellate and render all visible globe offsets of 2D continuous terrain
            renderGlobeOffset(rc, Globe.Offset.Center)
            renderGlobeOffset(rc, Globe.Offset.Right)
            renderGlobeOffset(rc, Globe.Offset.Left)
            // Reset globe offset for correct projection calculations after frame rendered
            rc.globe.offset = Globe.Offset.Center
        } else {
            // Tessellate and render single 3D terrain
            renderGlobeOffset(rc, Globe.Offset.Center)
        }
        rc.sortDrawables()
    }

    protected open fun renderGlobeOffset(rc: RenderContext, globeOffset: Globe.Offset) {
        rc.globe.offset = globeOffset

        // Check if 2D globe offset intersects current frustum
        if (rc.globe.is2D && !boundingBox.setToSector(fullSphere, rc.globe, 0f, 0f).intersectsFrustum(rc.frustum)) return

        // Prepare terrain for specified globe offset
        rc.terrain = rc.terrainTessellator.tessellate(rc)

        // Compute viewing distance and pixel size based on available terrain
        if (!rc.globe.is2D) adjustViewingParameters(rc)

        // Render the terrain picked object or transparent terrain and remember the last terrain for future intersect operations
        if (rc.isPickMode) renderTerrainPickedObject(rc) else renderTerrain(rc).also { lastTerrains[globeOffset] = rc.terrain }

        // Render all layers on specified globe offset
        rc.layers.render(rc)
    }

    protected open fun adjustViewingParameters(rc: RenderContext) {
        scratchRay.origin.copy(rc.cameraPoint)
        rc.modelview.extractForwardVector(scratchRay.direction)
        rc.viewingDistance = if (rc.terrain.intersect(scratchRay, scratchPoint)) {
            rc.lookAtPosition = rc.globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, Position())
            scratchPoint.distanceTo(rc.cameraPoint)
        } else rc.horizonDistance
        rc.pixelSize = rc.pixelSizeAtDistance(rc.viewingDistance)
    }

    protected open fun renderTerrain(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) return  // no terrain to pick

        // Enqueue drawable for processing on the OpenGL thread that displays terrain in the transparent color.
        // This is required to modify depth buffer and correctly cut the interior of 3D shapes by the terrain.
        val pool = rc.getDrawablePool(DrawableSurfaceColor.KEY)
        val drawable = obtain(pool)
        drawable.color.set(0f, 0f, 0f, 0f)
        drawable.opacity = 1.0f // Just to be sure to reset opacity
        drawable.program = rc.getShaderProgram(BasicShaderProgram.KEY) { BasicShaderProgram() }
        rc.offerSurfaceDrawable(drawable, Double.NEGATIVE_INFINITY)
    }

    protected open fun renderTerrainPickedObject(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) return  // no terrain to pick

        // Acquire a unique picked object ID for terrain.
        val pickedObjectId = rc.nextPickedObjectId()

        // Enqueue drawable for processing on the OpenGL thread that displays terrain in the unique pick color.
        val pool = rc.getDrawablePool(DrawableSurfaceColor.KEY)
        val drawable = obtain(pool)
        identifierToUniqueColor(pickedObjectId, drawable.color)
        drawable.opacity = 1.0f // Just to be sure to reset opacity
        drawable.program = rc.getShaderProgram(BasicShaderProgram.KEY) { BasicShaderProgram() }
        rc.offerSurfaceDrawable(drawable, Double.NEGATIVE_INFINITY)

        // If the pick ray intersects the terrain, enqueue a picked object that associates the terrain drawable with its
        // picked object ID and the intersection position.
        val pickRay = rc.pickRay
        if (pickRay != null && rc.terrain.intersect(pickRay, pickPoint)) {
            rc.globe.cartesianToGeographic(pickPoint.x, pickPoint.y, pickPoint.z, pickPos)
            rc.offerPickedObject(fromTerrain(pickedObjectId, pickPos))
        }
    }

    override fun drawFrame(dc: DrawContext) {
        if(dc.isPickMode) {
            dc.ensurePickFrameBuffer(dc.viewport.width, dc.viewport.height)
            dc.pickFramebuffer.bindFramebuffer(dc)
        }
        setViewport(dc)
        clearFrame(dc)
        uploadBuffers(dc)
        drawDrawables(dc)
        if (dc.isPickMode) {
            copyDepthToFramebuffer(dc)
            resolvePick(dc)
        }
    }

    protected open fun setViewport(dc: DrawContext) {
        // Set view port every frame to fix issues with shared EGL context
        dc.gl.viewport(0, 0, dc.viewport.width, dc.viewport.height)
    }

    protected open fun clearFrame(dc: DrawContext) {
        dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    protected open fun uploadBuffers(dc: DrawContext) {
        dc.uploadBuffers()
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

    protected open fun copyDepthToFramebuffer(dc: DrawContext) {
        val program = dc.depthToColorProgram ?: return
        if(!program.useProgram(dc)) return
        if(!dc.unitSquareBuffer.bindBuffer(dc)) return

        // bind backbuffer to render depth as color to it
        dc.bindFramebuffer(KglFramebuffer.NONE)
        dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        dc.activeTextureUnit(GL_TEXTURE0)
        val depthTexture = dc.pickFramebuffer.getAttachedTexture(GL_DEPTH_ATTACHMENT)
        if (!depthTexture.bindTexture(dc)) return // framebuffer texture failed to bind

        dc.gl.vertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)

        dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    protected open fun resolvePick(dc: DrawContext) {
        val pickedObjects = dc.pickedObjects ?: return
        if (pickedObjects.count == 0) return  // no eligible objects; avoid expensive calls to glReadPixels
        val pickViewport = dc.pickViewport ?: return
        val pickPointOnly = dc.pickPoint != null && pickViewport.width <= 3 && pickViewport.height <= 3
        var objectFound = false
        val invProjection = Matrix4(dc.modelviewProjection).invert()

        dc.pickPoint?.let { pickPoint ->
            // Read depth value at the pick point.
            val pickDepth = dc.readPixelDepth(pickPoint.x.roundToInt(), pickPoint.y.roundToInt())

            // Read the fragment color at the pick point.
            dc.pickFramebuffer.bindFramebuffer(dc)
            dc.readPixelColor(pickPoint.x.roundToInt(), pickPoint.y.roundToInt(), pickColor)

            // Convert the fragment color to a picked object ID. It returns zero if the color cannot indicate a picked
            // object ID, in which case no objects have been drawn at the pick point.
            val topObjectId = uniqueColorToIdentifier(pickColor)
            if (topObjectId != 0) {
                val topObject = pickedObjects.pickedObjectWithId(topObjectId)
                if (topObject != null) {
                    if (!topObject.isTerrain) {
                        objectFound = true
                        topObject.cartesianPoint = Vec3()
                        invProjection.unProject(
                            pickPoint.x,
                            pickPoint.y,
                            pickDepth.toDouble(),
                            dc.viewport,
                            topObject.cartesianPoint!!
                        )
                    } // Non-terrain object found in pick point
                    if (pickPointOnly || objectFound) {
                        topObject.markOnTop()
                        // Remove picked objects except top and terrain in case of object found or point only mode
                        // Using clearPickedObjects and two offerPickedObject is faster than keepTopAndTerrainObjects
                        val terrainObject = pickedObjects.terrainPickedObject
                        pickedObjects.clearPickedObjects()
                        pickedObjects.offerPickedObject(topObject)
                        // handles null objects and duplicate objects
                        if (terrainObject != null) pickedObjects.offerPickedObject(terrainObject)
                    }
                } else if (pickPointOnly) pickedObjects.clearPickedObjects() // no eligible objects drawn at the pick point
            } else if (pickPointOnly) pickedObjects.clearPickedObjects() // no objects drawn at the pick point
        }

        if (!pickPointOnly && !objectFound) {
            // Read fragment depths in the pick rectangle.
            dc.bindFramebuffer(KglFramebuffer.NONE)
            val pickDepths = dc.readPixelDepths(
                pickViewport.x,
                pickViewport.y,
                pickViewport.width,
                pickViewport.height
            )

            // Read fragment colors in the pick rectangle.
            dc.pickFramebuffer.bindFramebuffer(dc)
            val pickColors = dc.readPixelColors(
                pickViewport.x,
                pickViewport.y,
                pickViewport.width,
                pickViewport.height
            )

            val pixelCount = pickViewport.width * pickViewport.height
            for (i in 0 until pixelCount) {
                val pickColor = pickColors[i]
                val pickDepth = pickDepths[i]
                // Convert the fragment color to a picked object ID. This returns zero if the color cannot indicate a picked
                // object ID.
                val topObjectId = uniqueColorToIdentifier(pickColor)
                if (topObjectId != 0) {
                    val topObject = pickedObjects.pickedObjectWithId(topObjectId)
                    if (topObject?.isTerrain == false) {
                        topObject.markOnTop()
                        topObject.cartesianPoint = Vec3()
                        invProjection.unProject(
                            pickPoint.x,
                            pickPoint.y,
                            pickDepth.toDouble(),
                            dc.viewport,
                            topObject.cartesianPoint!!
                        )
                    }
                }
            }
        }

        dc.bindFramebuffer(KglFramebuffer.NONE)
    }
}