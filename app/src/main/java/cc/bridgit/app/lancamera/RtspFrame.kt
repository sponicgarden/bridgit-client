package cc.bridgit.app.lancamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20 as GL
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode a fresh RTSP video frame locally, without a visible view or an external relay. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object RtspFrame {
    fun player(context: Context, camera: LanCamera, main: Boolean = false): ExoPlayer {
        val sockets = LanNetwork.sockets(context, camera.host)
        val actor = cc.bridgit.app.outbound.Outbound.actor()
        val hint = cc.bridgit.app.outbound.Outbound.hint()
        val startedAt = System.currentTimeMillis()
        return ExoPlayer.Builder(context).build().apply {
            // C210 audio is G.711. This integration intentionally requests video only.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            setMediaSource(RtspMediaSource.Factory().setSocketFactory(sockets).setForceUseRtpTcp(true).setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(camera.rtsp(main))))
            addListener(object : Player.Listener {
                private var recorded = false
                override fun onRenderedFirstFrame() {
                    if (recorded) return
                    recorded = true
                    cc.bridgit.app.outbound.Outbound.on(actor, hint) {
                        cc.bridgit.app.outbound.Outbound.record("rtsp://${camera.host}:554/stream${if (main) 1 else 2}", "PLAY", "rtsp",
                            status = 200, startedAt = startedAt)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    cc.bridgit.app.outbound.Outbound.on(actor, hint) {
                        cc.bridgit.app.outbound.Outbound.record("rtsp://${camera.host}:554/stream${if (main) 1 else 2}", "PLAY", "rtsp",
                            status = 0, error = "Video connection failed", startedAt = startedAt)
                    }
                }
            })
            volume = 0f
        }
    }

    suspend fun snapshot(context: Context, camera: LanCamera): ByteArray {
        val renderer = FrameSurface()
        var player: ExoPlayer? = null
        try {
            return withTimeoutOrNull(12_000) {
                val surface = renderer.create()
                withContext(Dispatchers.Main) {
                    player = player(context, camera).apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                renderer.result.completeExceptionally(CameraFailure("Video connection failed. Check the Camera Account, Wi-Fi and available stream slots."))
                            }
                        })
                        setVideoSurface(surface); prepare(); playWhenReady = true
                    }
                }
                val bitmap = renderer.result.await()
                try { java.io.ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray() } }
                finally { bitmap.recycle() }
            } ?: throw CameraFailure("The camera did not deliver a fresh video frame in time.")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { player?.release() }
            renderer.close()
        }
    }
}

/** A small EGL pbuffer receives codec frames, so the Pet monitor also works away from its screen. */
private class FrameSurface {
    val result = CompletableDeferred<Bitmap>()
    private val thread = HandlerThread("lan-camera-frame").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var program = 0
    private var frames = 0
    private val width = 640
    private val height = 360
    suspend fun create(): Surface {
        val ready = CompletableDeferred<Surface>()
        handler.post {
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                window = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
                check(EGL14.eglMakeCurrent(display, window, window, context))
                val names = IntArray(1); GL.glGenTextures(1, names, 0)
                GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
                GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                program = GL.glCreateProgram()
                GL.glAttachShader(program, shader(GL.GL_VERTEX_SHADER, "attribute vec4 p; attribute vec4 uv; uniform mat4 transform; varying vec2 tex; void main(){gl_Position=p;tex=(transform*uv).xy;}"))
                GL.glAttachShader(program, shader(GL.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 tex; void main(){gl_FragColor=texture2D(image,tex);}"))
                GL.glLinkProgram(program)
                texture = SurfaceTexture(names[0]).apply {
                    setDefaultBufferSize(1280, 720)
                    setOnFrameAvailableListener({ frame ->
                        try {
                            frame.updateTexImage()
                            if (++frames >= 2 && !result.isCompleted) result.complete(read(frame))
                        } catch (e: Exception) { result.completeExceptionally(CameraFailure("Could not decode a camera frame.")) }
                    }, handler)
                }
                surface = Surface(texture)
                ready.complete(surface!!)
            } catch (e: Exception) { ready.completeExceptionally(CameraFailure("This device could not prepare the camera video decoder.")) }
        }
        return ready.await()
    }
    private fun read(frame: SurfaceTexture): Bitmap {
        GL.glViewport(0, 0, width, height); GL.glUseProgram(program)
        val transform = FloatArray(16); frame.getTransformMatrix(transform)
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(program, "transform"), 1, false, transform, 0)
        fun attr(name: String, data: FloatArray) {
            val b = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
            val loc = GL.glGetAttribLocation(program, name); GL.glEnableVertexAttribArray(loc); GL.glVertexAttribPointer(loc, 2, GL.GL_FLOAT, false, 0, b)
        }
        attr("p", floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
        attr("uv", floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f))
        GL.glUniform1i(GL.glGetUniformLocation(program, "image"), 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GL.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
        return Bitmap.createBitmap(raw, 0, 0, width, height, Matrix().apply { setScale(1f, -1f) }, false).also { if (it !== raw) raw.recycle() }
    }
    private fun shader(type: Int, source: String) = GL.glCreateShader(type).also { GL.glShaderSource(it, source); GL.glCompileShader(it) }
    suspend fun close() {
        val closed = CompletableDeferred<Unit>()
        handler.post {
            try {
                surface?.release(); texture?.release()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    if (program != 0) GL.glDeleteProgram(program)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, window); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
                }
            } finally { thread.quitSafely(); closed.complete(Unit) }
        }
        withContext(NonCancellable) { closed.await() }
    }
}
