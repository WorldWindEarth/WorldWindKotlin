package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.mercator.MercatorSector.Companion.gudermannianInverse
import earth.worldwind.util.Level
import kotlinx.browser.document
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.Image
import org.w3c.dom.url.URL
import kotlin.math.floor

/**
 * Constructs a tile with a specified sector, level, row and column.
 *
 * @param sector the sector spanned by the tile
 * @param level  the tile's level in a LevelSet
 * @param row    the tile's row within the specified level
 * @param column the tile's column within the specified level
 */
actual open class MercatorImageTile actual constructor(
    sector: MercatorSector, level: Level, row: Int, column: Int
): AbstractMercatorImageTile(sector, level, row, column) {
    companion object {
        private val srcCanvas = document.createElement("canvas") as HTMLCanvasElement
        private val srcCtx = srcCanvas.getContext("2d", js("{ willReadFrequently: true }")) as CanvasRenderingContext2D
        private val dstCanvas = document.createElement("canvas") as HTMLCanvasElement
        private val dstCtx = dstCanvas.getContext("2d") as CanvasRenderingContext2D
    }

    override suspend fun <Resource> process(resource: Resource) = if (resource is Image) {
        val width = resource.width
        val height = resource.height
        val widthDouble = resource.width.toDouble()
        val heightDouble = resource.height.toDouble()

        // Get source image data
        srcCanvas.width = width
        srcCanvas.height = height
        srcCtx.drawImage(resource, 0.0, 0.0, widthDouble, heightDouble)
        val srcData = srcCtx.getImageData(0.0, 0.0, widthDouble, heightDouble)

        // Prepare destination image data
        dstCanvas.width = width
        dstCanvas.height = height
        val dstData = dstCtx.createImageData(widthDouble, heightDouble)

        // Re-project mercator tile to equirectangular projection
        val sector = sector as MercatorSector
        val miny = sector.minLatPercent
        val maxy = sector.maxLatPercent
        for (y in 0 until height) {
            val sy = 1.0 - y / (height - 1.0)
            val lat = sy * sector.deltaLatitude.inDegrees + sector.minLatitude.inDegrees
            val dy = (1.0 - (gudermannianInverse(lat.degrees) - miny) / (maxy - miny)).coerceIn(0.0, 1.0)
            val srcRow = floor(dy * (height - 1)).toInt()
            for (x in 0 until width) {
                val src = 4 * (x + srcRow * width)
                val dst = 4 * (x + y * width)
                dstData.data[dst] = srcData.data[src]
                dstData.data[dst + 1] = srcData.data[src + 1]
                dstData.data[dst + 2] = srcData.data[src + 2]
                dstData.data[dst + 3] = srcData.data[src + 3]
            }
        }

        // Replace image source with transformed canvas image data
        dstCtx.putImageData(dstData, 0.0, 0.0)
        //resource.src = dstCanvas.toDataURL() // This approach is performance ineffective thus replaced by toBlob()
        dstCanvas.toBlob({
            // Setting new src will call onLoad or onError in RenderResourceCache and continue image retrieval process
            if (it != null) resource.src = URL.createObjectURL(it)
            // Call image.onError in RenderResourceCache to fail retrieval and mark resource as absent
            else resource.onerror?.invoke("Error saving canvas to Blob", "", 0, 0, null) as Unit
        })

        resource // Do not call super.process to prevent unnecessary onLoad event processing
    } else super.process(resource)
}