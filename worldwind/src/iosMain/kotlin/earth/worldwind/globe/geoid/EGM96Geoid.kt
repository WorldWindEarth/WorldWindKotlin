@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/**
 * iOS port of EGM96Geoid. Resolves the offsets file via the moko-resources [AssetResource]
 * (which carries its own `NSBundle` reference to the packed `:worldwind` bundle), then
 * parses 16-bit big-endian signed shorts into a Kotlin [ShortArray].
 *
 * On macOS hosts the moko-resources pack action runs as part of the iOS link, so the
 * `EGM96.dat` file is already inside the framework's resource bundle — no host-app
 * intervention required. On Windows hosts the pack action is stripped (the bundle name's
 * `:` is illegal in NTFS), in which case host apps must either provide their own bundle
 * with the matching `CFBundleIdentifier` or pass an alternate [AssetResource] manually.
 */
actual open class EGM96Geoid actual constructor(
    offsetsFile: AssetResource, scope: CoroutineScope
) : AbstractEGM96Geoid(offsetsFile, scope) {
    private var deltas: ShortArray? = null

    actual override val isInitialized get() = deltas != null

    actual override fun release() {
        super.release()
        deltas = null
    }

    actual override suspend fun loadData(offsetsFile: AssetResource) {
        deltas = withContext(Dispatchers.Default) {
            // moko's AssetResource carries its bundle + (fileName, extension) directly. Falling
            // back to NSBundle.mainBundle covers hosts that ship the file at the .app root for
            // historical or build-system reasons (e.g. Windows-host stripped moko packing).
            val basename = offsetsFile.fileName
            val ext = offsetsFile.extension
            val path = offsetsFile.bundle.pathForResource(basename, ext)
                ?: NSBundle.mainBundle.pathForResource(basename, ext)
                ?: error("EGM96 resource not found in moko bundle or main bundle: " +
                    "${offsetsFile.originalPath} " +
                    "(check that moko-resources packed the iOS bundle, or include the file " +
                    "in the host .app)")
            val data = NSData.dataWithContentsOfFile(path)
                ?: error("Failed to read EGM96 data from $path")
            val length = data.length.toInt()
            val shortCount = length / 2
            val out = ShortArray(shortCount)
            val bytesPtr = data.bytes ?: return@withContext out
            val bytes = bytesPtr.reinterpret<ByteVar>()
            // Big-endian: hi byte first.
            for (i in 0 until shortCount) {
                val hi = bytes[2 * i].toInt() and 0xFF
                val lo = bytes[2 * i + 1].toInt() and 0xFF
                out[i] = ((hi shl 8) or lo).toShort()
            }
            out
        }
    }

    actual override fun getValue(k: Int): Short {
        val d = deltas ?: return 0
        return if (k in 0 until d.size) d[k] else 0
    }
}
