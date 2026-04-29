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
    private val invModelviewProjection = Matrix4()

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
        if (dc.isPickMode) {
            dc.ensurePickFramebuffer(dc.viewport.width, dc.viewport.height)
            dc.pickFramebuffer.bindFramebuffer(dc)
        }
        setViewport(dc)
        clearFrame(dc)
        uploadBuffers(dc)
        drawDrawables(dc)
        if (dc.isPickMode) {
            // Skip the depth-to-color blit when no shapes were drawn (most clicks on empty space).
            if (dc.pickedObjects?.count != 0) copyDepthToReadbackFramebuffer(dc)
            resolvePick(dc)
            dc.bindFramebuffer(KglFramebuffer.NONE)
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

    /**
     * RG-packs the pick framebuffer's depth attachment into the readback framebuffer so its
     * 16-bit depth can be retrieved via `glReadPixels` (DEPTH_COMPONENT isn't portably readable
     * on WebGL1 / GLES2). No-op when the engine hasn't provided a [DepthToColorProgram].
     */
    protected open fun copyDepthToReadbackFramebuffer(dc: DrawContext) {
        val program = dc.depthToColorProgram ?: return
        if (!program.useProgram(dc)) return
        if (!dc.unitSquareBuffer.bindBuffer(dc)) return

        dc.pickDepthReadbackFramebuffer.bindFramebuffer(dc)
        dc.gl.clear(GL_COLOR_BUFFER_BIT)

        dc.activeTextureUnit(GL_TEXTURE0)
        val depthTexture = dc.pickFramebuffer.getAttachedTexture(GL_DEPTH_ATTACHMENT)
        if (!depthTexture.bindTexture(dc)) return

        dc.gl.vertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
        dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    protected open fun resolvePick(dc: DrawContext) {
        val pickedObjects = dc.pickedObjects ?: return
        if (pickedObjects.count == 0) return  // no eligible objects; avoid expensive calls to glReadPixels
        val pickViewport = dc.pickViewport ?: return
        val pickPointOnly = dc.pickPoint != null && pickViewport.width <= 3 && pickViewport.height <= 3
        var objectFound = false

        dc.pickPoint?.let { pickPoint ->
            val px = pickPoint.x.roundToInt()
            val py = pickPoint.y.roundToInt()

            dc.pickFramebuffer.bindFramebuffer(dc)
            dc.readPixelColor(px, py, pickColor)
            val topObjectId = uniqueColorToIdentifier(pickColor)
            val topObject = if (topObjectId != 0) pickedObjects.pickedObjectWithId(topObjectId) else null

            if (topObject == null) {
                if (pickPointOnly) pickedObjects.clearPickedObjects()
                return@let
            }
            if (!topObject.isTerrain) {
                objectFound = true
                // Reconstruct the Cartesian point only when a non-terrain shape was actually hit;
                // a 4x4 invert is wasted on terrain-only picks and on misses.
                invModelviewProjection.copy(dc.modelviewProjection).invert()
                dc.pickDepthReadbackFramebuffer.bindFramebuffer(dc)
                val depth = dc.readPixelDepth(px, py)
                topObject.cartesianPoint = Vec3().also {
                    invModelviewProjection.unProject(pickPoint.x, pickPoint.y, depth.toDouble(), dc.viewport, it)
                }
            }
            if (pickPointOnly || objectFound) {
                topObject.markOnTop()
                // Remove non-top non-terrain picks. clearPickedObjects + two offers is faster
                // than keepTopAndTerrainObjects.
                val terrainObject = pickedObjects.terrainPickedObject
                pickedObjects.clearPickedObjects()
                pickedObjects.offerPickedObject(topObject)
                if (terrainObject != null) pickedObjects.offerPickedObject(terrainObject)
            }
        }

        if (pickPointOnly || objectFound) return

        // Rect path: pair each pixel's color with its depth at the same index.
        dc.pickDepthReadbackFramebuffer.bindFramebuffer(dc)
        val pickDepths = dc.readPixelDepths(pickViewport.x, pickViewport.y, pickViewport.width, pickViewport.height)
        dc.pickFramebuffer.bindFramebuffer(dc)
        val pickColors = dc.readPixelColorList(pickViewport.x, pickViewport.y, pickViewport.width, pickViewport.height)
        var invMVPReady = false

        for (i in pickColors.indices) {
            val topObjectId = uniqueColorToIdentifier(pickColors[i])
            if (topObjectId == 0) continue
            val topObject = pickedObjects.pickedObjectWithId(topObjectId) ?: continue
            if (topObject.isTerrain) continue
            topObject.markOnTop()
            // Set the Cartesian point on the first pixel that hits each object.
            if (topObject.cartesianPoint != null) continue
            if (!invMVPReady) {
                invModelviewProjection.copy(dc.modelviewProjection).invert()
                invMVPReady = true
            }
            val px = pickViewport.x + (i % pickViewport.width)
            val py = pickViewport.y + (i / pickViewport.width)
            topObject.cartesianPoint = Vec3().also {
                invModelviewProjection.unProject(
                    px.toDouble(), py.toDouble(), pickDepths[i].toDouble(), dc.viewport, it
                )
            }
        }
    }
}