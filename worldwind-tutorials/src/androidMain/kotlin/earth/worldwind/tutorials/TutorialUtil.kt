package earth.worldwind.tutorials

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object TutorialUtil {

    fun unpackAsset(context: Context, fileName: String) =
        File(context.cacheDir, fileName).also { file ->
            if (!file.exists()) FileOutputStream(file).buffered().use { context.assets.open(fileName).buffered().copyTo(it) }
        }

}