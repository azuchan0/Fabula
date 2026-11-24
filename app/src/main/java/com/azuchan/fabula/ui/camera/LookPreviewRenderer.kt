package com.azuchan.fabula.ui.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws the camera preview into a SurfaceView through OpenGL ES so the colour look is visible
 * live: the camera renders into a SurfaceTexture, and every frame is drawn to the view with the
 * look's 3x3 matrix and per-channel curves (the same tables applied to saved photos).
 */
class LookPreviewRenderer(private val outputSurface: Surface) {
    private val thread = HandlerThread("FabulaPreviewGl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var cameraTexture = 0
    private var lutTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val textureMatrix = FloatArray(16)
    private var lookMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var lookEnabled = false
    private var pendingLook: LookTables? = null
    private var released = false
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD).position(0) }

    /** False when EGL could not be set up; the caller then falls back to the plain preview. */
    val ready: Boolean

    init {
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = runCatching { setUp() }.onFailure { Log.e(TAG, "Preview GL setup failed.", it) }.isSuccess
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        ready = ok
        if (!ready) release()
    }

    /** The surface the camera writes the preview into, with its buffers sized to [size]. */
    fun cameraSurface(size: Size): Surface? {
        if (!ready || released) return null
        surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        return cameraSurface
    }

    fun setLook(look: ColorLook) {
        val tables = if (look == ColorLook.STANDARD) null else look.tables()
        handler.post {
            if (released) return@post
            if (tables == null) {
                lookEnabled = false
            } else {
                pendingLook = tables
            }
            drawFrame(updateTexture = false)
        }
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            surfaceTexture?.setOnFrameAvailableListener(null)
            cameraSurface?.release()
            surfaceTexture?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, windowSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        windowSurface = EGL14.eglCreateWindowSurface(display, configs[0], outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(windowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        cameraTexture = textures[0]
        lutTexture = textures[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        setLinearClamp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        setLinearClamp(GLES20.GL_TEXTURE_2D)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 3, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, identityCurves(),
        )

        surfaceTexture = SurfaceTexture(cameraTexture).apply {
            setOnFrameAvailableListener({ drawFrame(updateTexture = true) }, handler)
        }
        cameraSurface = Surface(surfaceTexture)
    }

    private fun drawFrame(updateTexture: Boolean) {
        if (released || windowSurface == EGL14.EGL_NO_SURFACE) return
        val texture = surfaceTexture ?: return
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) return
        if (updateTexture) {
            runCatching { texture.updateTexImage() }.onFailure { return }
        }
        pendingLook?.let { tables ->
            pendingLook = null
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, 256, 3,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(tables.curves),
            )
            // GLSL matrices are column-major; the look matrix is row-major.
            val m = tables.matrix
            lookMatrix = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
            lookEnabled = true
        }
        texture.getTransformMatrix(textureMatrix)

        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_HEIGHT, height, 0)
        GLES20.glViewport(0, 0, width[0], height[0])
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCamera"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCurves"), 1)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, textureMatrix, 0)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uLook"), 1, false, lookMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLookOn"), if (lookEnabled) 1f else 0f)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, windowSurface)
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun setLinearClamp(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun identityCurves(): ByteBuffer =
        ByteBuffer.allocateDirect(768).apply {
            repeat(3) { for (i in 0 until 256) put(i.toByte()) }
            position(0)
        }

    private companion object {
        const val TAG = "LookPreviewRenderer"

        // x, y, u, v for a full-screen triangle strip.
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // Curves texture: 256 x 3 luminance, one row per channel; sample at texel centres.
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCamera;
            uniform sampler2D uCurves;
            uniform mat3 uLook;
            uniform float uLookOn;
            varying vec2 vTexCoord;
            float curve(float value, float row) {
                return texture2D(uCurves, vec2(value * (255.0 / 256.0) + 0.5 / 256.0, row)).r;
            }
            void main() {
                vec3 color = texture2D(uCamera, vTexCoord).rgb;
                if (uLookOn > 0.5) {
                    color = clamp(uLook * color, 0.0, 1.0);
                    color = vec3(curve(color.r, 0.5 / 3.0), curve(color.g, 1.5 / 3.0), curve(color.b, 2.5 / 3.0));
                }
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
