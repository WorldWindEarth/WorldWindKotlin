package earth.worldwind.layer

import earth.worldwind.MR
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawableScreenTexture
import earth.worldwind.geom.Location
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.shape.ScreenImage

class WorldMapLayer : AbstractLayer("World Map") {
    override var isPickEnabled = false

    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    // mini-map width in density-independent pixels (dp)
    var mapWidthDp = 150.0
    // margin from the chosen corner in density-independent pixels (dp)
    var margin = 11.0
    var corner = Corner.BOTTOM_RIGHT

    private val mapImageSource = ImageSource.fromResource(MR.images.worldwind_worldtopobathy2004053)
    private val mapImage = ScreenImage(mapImageSource)

    // dot drawn over the map to indicate camera position
    private val dotColor = Color(1f, 0f, 0f, 1f)
    private val dotTransform = Matrix4()

    // rendered bounds (physical pixels, OpenGL coord system) – updated each frame
    private var mapX = 0.0
    private var mapY = 0.0
    private var mapW = 0.0
    private var mapH = 0.0

    override fun doRender(rc: RenderContext) {
        val texture = rc.getTexture(mapImageSource) ?: return

        val tw = texture.width.toDouble()
        val th = texture.height.toDouble()
        val mapW = mapWidthDp * rc.densityFactor
        val mapH = mapW * (th / tw)

        // Place map at the configured corner
        val scaledMargin = margin * rc.densityFactor
        val mapX = when (corner) {
            Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> scaledMargin
            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> rc.viewport.width - mapW - scaledMargin
        }
        val mapY = when (corner) {
            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> scaledMargin
            Corner.TOP_LEFT, Corner.TOP_RIGHT -> rc.viewport.height - mapH - scaledMargin
        }

        // Store bounds for click detection
        this.mapX = mapX; this.mapY = mapY; this.mapW = mapW; this.mapH = mapH

        // Render map image
        mapImage.screenOffset = Offset(OffsetMode.PIXELS, mapX, OffsetMode.PIXELS, mapY)
        mapImage.imageOffset = Offset(OffsetMode.PIXELS, 0.0, OffsetMode.PIXELS, 0.0)
        mapImage.imageScale = mapW / tw
        mapImage.render(rc)

        // Render camera-position dot
        val lat = rc.camera.position.latitude.inDegrees
        val lon = rc.camera.position.longitude.inDegrees
        val dotX = mapX + (lon + 180.0) / 360.0 * mapW
        val dotY = mapY + (lat + 90.0) / 180.0 * mapH
        val dotSize = 4.0 * rc.densityFactor

        dotTransform.setToIdentity()
        dotTransform.setTranslation(dotX - dotSize * 0.5, dotY - dotSize * 0.5, 0.0)
        dotTransform.setScale(dotSize, dotSize, 1.0)

        val pool = rc.getDrawablePool(DrawableScreenTexture.KEY)
        val drawable = DrawableScreenTexture.obtain(pool)
        drawable.program = rc.getShaderProgram(BasicShaderProgram.KEY) { BasicShaderProgram() }
        drawable.unitSquareTransform.copy(dotTransform)
        drawable.color.copy(dotColor)
        drawable.opacity = rc.currentLayer.opacity
        drawable.texture = null
        drawable.enableDepthTest = false
        rc.offerScreenDrawable(drawable, 0.0)
    }

    /**
     * Handle a tap/click at (x, y) in top-left screen coordinates. Navigates the globe to the tapped location.
     * Returns true if the tap was inside the minimap.
     */
    fun handleClick(x: Double, y: Double, viewportHeight: Int, engine: WorldWind): Boolean {
        val glY = viewportHeight - y
        if (x < mapX || x > mapX + mapW || glY < mapY || glY > mapY + mapH) return false
        val lat = (glY - mapY) / mapH * 180.0 - 90.0
        val lon = (x - mapX) / mapW * 360.0 - 180.0
        engine.goToAnimator.goTo(Location.fromDegrees(lat, lon))
        return true
    }
}
