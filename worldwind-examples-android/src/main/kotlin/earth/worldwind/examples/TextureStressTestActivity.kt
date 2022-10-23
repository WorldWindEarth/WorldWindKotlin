package earth.worldwind.examples

import android.graphics.Bitmap
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.ShowTessellationLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageSource.Companion.fromBitmapFactory
import earth.worldwind.shape.SurfaceImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class TextureStressTestActivity : BasicGlobeActivity() {
    protected val layer = RenderableLayer()
    protected val firstSector = Sector()
    protected val sector = Sector()
    protected lateinit var bitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_texture_stress_test)
        aboutBoxText = "Continuously allocates OpenGL texture objects to test the effect of an excessive number of textures on the WorldWind render resource cache."

        // Setting up the WorldWindow to display the tessellation layer and a layer of surface images. We use a minimal
        // layer configuration in order to gather precise metrics on memory usage.
        wwd.engine.layers.apply {
            clearLayers()
            addLayer(ShowTessellationLayer())
            addLayer(layer)
        }

        // Position the viewer so that the surface images will be visible as they're added.
        firstSector.setDegrees(35.0, 10.0, 0.5, 0.5)
        sector.copy(firstSector)
        wwd.engine.camera.position.setDegrees(37.5, 15.0, 1.0e6)

        // Allocate a 32-bit 1024 x 1024 bitmap that we'll use to create all the OpenGL texture objects in this test.
        val colors = IntArray(1024 * 1024)
        colors.fill(-0xff0100)
        bitmap = Bitmap.createBitmap(colors, 1024, 1024, Bitmap.Config.ARGB_8888)
    }

    protected open fun addImage() {
        // Create an image source with a unique factory instance. This pattern is used in order to force WorldWind to
        // allocate a new OpenGL texture object for each surface image from a single bitmap instance.
        val imageSource = fromBitmapFactory(object : ImageSource.BitmapFactory {
            override fun createBitmap() = bitmap
        })

        // Add the surface image to this test's layer.
        layer.addRenderable(SurfaceImage(Sector(sector), imageSource))
        wwd.requestRedraw()

        // Advance to the next surface image's location.
        if (sector.maxLongitude.inDegrees < firstSector.minLongitude.inDegrees + firstSector.deltaLongitude.inDegrees * 20)
            sector.set(
                sector.minLatitude, sector.minLongitude + sector.deltaLongitude.plusDegrees(0.1),
                sector.deltaLatitude, sector.deltaLongitude
            )
        else sector.set(
            sector.minLatitude + sector.deltaLatitude.plusDegrees(0.1),
            firstSector.minLongitude, sector.deltaLatitude, sector.deltaLongitude
        )

        // Add another image after the configured delay.
        lifecycleScope.launch {
            delay(ADD_IMAGE_DELAY)
            addImage()
        }
    }

    override fun onResume() {
        super.onResume()
        // Add images to the WorldWindow at a regular interval.
        lifecycleScope.launch {
            delay(ADD_IMAGE_DELAY)
            addImage()
        }
    }

    companion object {
        protected const val ADD_IMAGE_DELAY = 1000L
    }
}