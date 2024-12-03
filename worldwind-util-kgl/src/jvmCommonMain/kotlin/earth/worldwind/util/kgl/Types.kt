package earth.worldwind.util.kgl

actual data class KglShader(val id: Int = 0) {
    actual companion object{ actual val NONE = KglShader() }
    actual fun isValid() = id != 0
}

actual data class KglProgram(val id: Int = 0) {
    actual companion object{ actual val NONE = KglProgram() }
    actual fun isValid() = id != 0
}

actual data class KglUniformLocation(val id: Int = -1) {
    actual companion object{ actual val NONE = KglUniformLocation() }
    actual fun isValid() = id != 0
}

actual data class KglBuffer(val id: Int = 0) {
    actual companion object{ actual val NONE = KglBuffer() }
    actual fun isValid() = id != 0
}

actual data class KglTexture(val id: Int = 0) {
    actual companion object{ actual val NONE = KglTexture() }
    actual fun isValid() = id != 0
}

actual data class KglFramebuffer(val id: Int = 0) {
    actual companion object{ actual val NONE = KglFramebuffer() }
    actual fun isValid() = id != 0
}
