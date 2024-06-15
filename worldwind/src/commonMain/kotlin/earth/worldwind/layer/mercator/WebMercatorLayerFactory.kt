package earth.worldwind.layer.mercator

import earth.worldwind.layer.mercator.MercatorTiledImageLayer.Companion.buildTiledSurfaceImage
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.locale.language
import kotlin.random.Random

object WebMercatorLayerFactory {
    private const val OPEN_BRACKET = '{'
    private const val CLOSED_BRACKET = '}'
    private const val I_EXPRESSION = "{i}"
    private const val X_COORDINATE = "{x}"
    private const val Y_COORDINATE = "{y}"
    private const val Z_COORDINATE = "{z}"
    private const val LANGUAGE = "{lang}"
    private const val RAND_PREFIX = "{rand="

    fun createLayer(
        urlTemplate: String, imageFormat: String = "image/png", transparent: Boolean = false,
        name: String? = null, numLevels: Int = 22, tileSize: Int = 256, levelOffset: Int = 1
    ): WebMercatorImageLayer {
        val urlParts = parseUrl(urlTemplate)
        val randomValue = urlParts.find { it.startsWith(RAND_PREFIX) }
        val randomValues = randomValue?.removeSurrounding(RAND_PREFIX, "}")?.split(",")
        val tileFactory = object : MercatorTileFactory {
            override val contentType = "Web Mercator"

            override fun getImageSource(x: Int, y: Int, z: Int): ImageSource {
                val resultServerUrl = StringBuilder()
                urlParts.forEach {
                    resultServerUrl.append(
                        when (it) {
                            X_COORDINATE -> x
                            Y_COORDINATE -> y
                            Z_COORDINATE -> z
                            LANGUAGE -> language
                            randomValue -> randomValues?.get(Random.nextInt(randomValues.size))
                            I_EXPRESSION -> x % 4 + y % 4 * 4
                            else -> it
                        }
                    )
                }
                return ImageSource.fromUrlString(resultServerUrl.toString())
            }
        }
        val tiledSurfaceImage = buildTiledSurfaceImage(tileFactory, numLevels, tileSize, transparent, levelOffset)
        return WebMercatorImageLayer(urlTemplate, imageFormat, transparent, name, tiledSurfaceImage)
    }

    private fun parseUrl(serverUrl: String): List<String> {
        val result = mutableListOf<String>()
        val builder = StringBuilder()
        serverUrl.toCharArray().forEachIndexed { index, char ->
            if (char == OPEN_BRACKET) {
                result.add(builder.toString())
                builder.clear()
            }
            builder.append(char)
            if (char == CLOSED_BRACKET) {
                result.add(builder.toString())
                builder.clear()
            }
            if (index == serverUrl.length - 1 && char != CLOSED_BRACKET) {
                result.add(builder.toString())
                builder.clear()
            }
        }
        return result
    }
}