package earth.worldwind.util.kgl

import android.opengl.EGL14.EGL_OPENGL_ES2_BIT
import android.opengl.EGL14.EGL_RGB_BUFFER
import android.opengl.GLSurfaceView.EGLConfigChooser
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

class TranslucentEGLConfigChooser : EGLConfigChooser {

    private var configSize = 64

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
        val attribList = intArrayOf(
            EGL10.EGL_LEVEL, 0,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_COLOR_BUFFER_TYPE, EGL_RGB_BUFFER,
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 16,
// Antialiasing causes gaps to appear at the edges of terrain tiles.
//            EGL10.EGL_SAMPLE_BUFFERS, 1, // Enable antialiasing
//            EGL10.EGL_SAMPLES, 4, // 4x MSAA.
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(configSize)
        val numConfig = IntArray(1)
        require(egl.eglChooseConfig(display, attribList, configs, configSize, numConfig)) { "eglChooseConfig failed" }
        configSize = numConfig[0]
        return if (configSize > 0) configs[0] else throw IllegalArgumentException("No config chosen")
    }
}