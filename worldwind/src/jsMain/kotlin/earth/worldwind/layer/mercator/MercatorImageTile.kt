package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.mercator.MercatorSector.Companion.gudermannianInverse
import earth.worldwind.util.Level
import kotlinx.browser.document
import org.khronos.webgl.Uint8ClampedArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.Image
import org.w3c.dom.ImageBitmap
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

    override suspend fun <Resource> process(resource: Resource): Resource = when (resource) {
        // URL retrieval path: reproject in shared canvases, hand a Blob URL back to RenderResourceCache via src
        is Image -> {
            val width = resource.width
            val height = resource.height

            // Get source image data
            srcCanvas.width = width
            srcCanvas.height = height
            srcCtx.drawImage(resource, 0.0, 0.0, width.toDouble(), height.toDouble())
            val srcData = srcCtx.getImageData(0.0, 0.0, width.toDouble(), height.toDouble())

            // Prepare destination image data
            dstCanvas.width = width
            dstCanvas.height = height
            val dstData = dstCtx.createImageData(width.toDouble(), height.toDouble())

            // Re-project mercator tile to equirectangular projection
            reproject(width, height, srcData.data, dstData.data)

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
        }
        // ImageFactory retrieval path (cached tiles): reproject into a fresh canvas and return it as the texture source
        is ImageBitmap -> {
            val width = resource.width
            val height = resource.height
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            canvas.width = width
            canvas.height = height
            val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
            ctx.drawImage(resource, 0.0, 0.0, width.toDouble(), height.toDouble())
            val srcData = ctx.getImageData(0.0, 0.0, width.toDouble(), height.toDouble())
            val dstData = ctx.createImageData(width.toDouble(), height.toDouble())
            reproject(width, height, srcData.data, dstData.data)
            ctx.putImageData(dstData, 0.0, 0.0)
            canvas
        }
        else -> super.process(resource)
    }.unsafeCast<Resource>()

    /** Remap source rows into equirectangular space via the Mercator inverse-Gudermannian lookup. */
    private fun reproject(width: Int, height: Int, srcBytes: Uint8ClampedArray, dstBytes: Uint8ClampedArray) {
        val sector = sector as MercatorSector
        val miny = sector.minLatPercent
        val maxy = sector.maxLatPercent
        val rowBytes = width * 4
        for (y in 0 until height) {
            val sy = 1.0 - y / (height - 1.0)
            val lat = sy * sector.deltaLatitude.inDegrees + sector.minLatitude.inDegrees
            val dy = (1.0 - (gudermannianInverse(lat.degrees) - miny) / (maxy - miny)).coerceIn(0.0, 1.0)
            val srcRow = floor(dy * (height - 1)).toInt()
            val srcOff = srcRow * rowBytes
            dstBytes.set(srcBytes.subarray(srcOff, srcOff + rowBytes), y * rowBytes)
        }
    }
}