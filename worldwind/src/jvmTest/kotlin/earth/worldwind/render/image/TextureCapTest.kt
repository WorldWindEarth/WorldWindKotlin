package earth.worldwind.render.image

import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Verifies [ImageOptions.maxDimension] actually downsamples a decoded image (JVM ImageIO subsampling). */
class TextureCapTest {

    @Test
    fun maxDimension_downsamplesAtDecode() = runBlocking {
        val png = File.createTempFile("wwtex", ".png").apply { deleteOnExit() }
        ImageIO.write(BufferedImage(2048, 1024, BufferedImage.TYPE_INT_ARGB), "png", png)

        ImageDecoder().use { decoder ->
            val capped = decoder.decodeImage(ImageSource.fromFile(png), ImageOptions().apply { maxDimension = 512 })!!
            assertTrue(capped.width <= 512 && capped.height <= 512, "capped to ${capped.width}x${capped.height}")

            val full = decoder.decodeImage(ImageSource.fromFile(png), ImageOptions())!!
            assertTrue(full.width == 2048 && full.height == 1024, "uncapped is native ${full.width}x${full.height}")
        }
    }
}
