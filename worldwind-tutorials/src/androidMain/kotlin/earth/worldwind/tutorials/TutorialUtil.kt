package earth.worldwind.tutorials

import android.content.Context
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

object TutorialUtil {
    fun unpackAsset(context: Context, fileName: String): File? {
        return try {
            val file = File(context.cacheDir, fileName)
            if (file.exists()) return file
            BufferedInputStream(context.assets.open(fileName)).use { inputStream ->
                BufferedOutputStream(FileOutputStream(file)).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var count: Int
                    while (inputStream.read(buffer).also { count = it } != -1) outputStream.write(buffer, 0, count)
                    outputStream.flush()
                    file
                }
            }
        } catch (ex: Exception) {
            logMessage(
                Logger.ERROR, "TutorialUtil", "unpackAsset",
                "Exception unpacking $fileName", ex
            )
            null
        }
    }
}