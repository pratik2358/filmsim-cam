package app.fujifilmcam.filmcam

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Picks a GLES 3 config that can also draw into a video encoder (EGL_RECORDABLE_ANDROID), if the phone has one. */
class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        fun pick(recordable: Boolean): EGLConfig? {
            val attrs = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */) +
                (if (recordable) intArrayOf(0x3142 /* EGL_RECORDABLE_ANDROID */, 1) else intArrayOf()) + EGL10.EGL_NONE
            val out = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            return if (egl.eglChooseConfig(display, attrs, out, 1, n) && n[0] > 0) out[0] else null
        }
        return pick(true) ?: pick(false) ?: error("no OpenGL ES 3 support")
    }
}

/** Portrait-mode person mask (0 = background, 255 = person), upright and un-mirrored. */
class Mask(val data: ByteBuffer, val width: Int, val height: Int, val hasPerson: Boolean)

/**
 * Draws the camera (or a still photo) with the look applied, using OpenGL ES 3:
 * image → optional portrait background blur → baked 3D LUT (look + strength + picture settings) → grain.
 * The same shader renders full-resolution photos, so the saved picture matches the preview.
 */
class FilmRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {

    // ---- Settings (written on the main thread, read on the GL thread) ----
    @Volatile private var lut: ByteArray? = null
    @Volatile private var lutVersion = 0
    @Volatile var grain = 0f
    /** Front camera: show it mirrored, like a mirror. */
    @Volatile var mirror = false
    @Volatile var portrait = false
    @Volatile var aperture = 2.8f
    @Volatile private var rotation = 90                 // degrees to turn the camera buffer upright
    @Volatile private var transformRotates = false      // the SurfaceTexture matrix already includes that rotation
    @Volatile private var bufferW = 0; @Volatile private var bufferH = 0
    @Volatile private var still: Bitmap? = null
    @Volatile private var stillVersion = 0
    @Volatile private var mask: Mask? = null
    @Volatile private var maskVersion = 0
    @Volatile var sharpness = 0f                        // shader amounts (Adjustments.sharpAmount / clarityAmount)
    @Volatile var clarity = 0f
    @Volatile var frozen = false                        // hold the last frame while a photo is processed

    /** Visible part of the source (normalized, upright, top-left origin); also what photos are cropped to. */
    @Volatile var crop = RectF(0f, 0f, 1f, 1f); private set
    /** Called (on the GL thread) once the camera surface exists, e.g. after the GL context is recreated. */
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    fun requestRender() = view.requestRender()
    fun setLut(data: ByteArray?) { lut = data; lutVersion++; view.requestRender() }
    fun setStill(bmp: Bitmap?) { still = bmp; stillVersion++; view.requestRender() }
    fun setMask(m: Mask?) { mask = m; maskVersion++ }
    /** `hasCameraTransform`: the camera writes its orientation into the surface (SurfaceTexture applies it). */
    fun setCameraBuffer(w: Int, h: Int, rotationDegrees: Int, hasCameraTransform: Boolean) {
        bufferW = w; bufferH = h; rotation = rotationDegrees; transformRotates = hasCameraTransform
    }

    /** Upright size of what's being shown (camera frame or still). */
    fun sourceSize(): Pair<Int, Int> {
        still?.let { return it.width to it.height }
        if (bufferW == 0) return 3 to 4
        return if (rotation % 180 == 0) bufferW to bufferH else bufferH to bufferW
    }

    // ---- GL state ----
    private var progOes = 0; private var prog2d = 0
    private var camTex = 0; private var surfaceTexture: SurfaceTexture? = null
    private var lutTex = 0; private var lutUploaded = -1
    private var stillTex = 0; private var stillUploaded = -1
    private var maskTex = 0; private var maskUploaded = -1
    private var bgFbo = 0; private var bgTex = 0; private var bgW = 0; private var bgH = 0
    private val stMatrix = FloatArray(16)
    private var viewW = 1; private var viewH = 1
    private var encSurface: EGLSurface? = null; private var encW = 0; private var encH = 0
    var maxTextureSize = 4096; private set
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progOes = program(VERT, frag(oes = true)); prog2d = program(VERT, frag(oes = false))
        val ids = IntArray(5); glGenTextures(5, ids, 0)
        camTex = ids[0]; lutTex = ids[1]; stillTex = ids[2]; maskTex = ids[3]; bgTex = ids[4]
        lutUploaded = -1; stillUploaded = -1; maskUploaded = -1; bgW = 0
        encSurface = null   // a new context: any recording surface belonged to the old one
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        linear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        for (t in intArrayOf(stillTex, maskTex)) { glBindTexture(GL_TEXTURE_2D, t); linear(GL_TEXTURE_2D) }
        glBindTexture(GL_TEXTURE_3D, lutTex); linear(GL_TEXTURE_3D)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
        val fb = IntArray(1); glGenFramebuffers(1, fb, 0); bgFbo = fb[0]
        val mx = IntArray(1); glGetIntegerv(GL_MAX_TEXTURE_SIZE, mx, 0); maxTextureSize = min(mx[0], 8192)

        surfaceTexture?.release()
        val st = SurfaceTexture(camTex)
        st.setOnFrameAvailableListener { view.requestRender() }
        surfaceTexture = st
        onSurfaceTextureReady?.invoke(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { viewW = width; viewH = height }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture
        if (st != null) runCatching { st.updateTexImage(); st.getTransformMatrix(stMatrix) }
        if (frozen) return
        syncTextures()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
        val stillBmp = still
        val (sw, sh) = sourceSize()
        if (stillBmp != null) {
            // Library photos are shown whole (letterboxed).
            val s = min(viewW.toFloat() / sw, viewH.toFloat() / sh)
            val w = (sw * s).roundToInt(); val h = (sh * s).roundToInt()
            val portraitOn = portrait && mask?.hasPerson == true
            if (portraitOn || clarity != 0f) blurPass(prog2d, GL_TEXTURE_2D, stillTex, identityTexMat(), mirrorImage = false)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport((viewW - w) / 2, (viewH - h) / 2, w, h)
            draw(prog2d, GL_TEXTURE_2D, stillTex, identityTexMat(), RectF(0f, 0f, 1f, 1f), w, h,
                flipY = false, mirror = false, seed = 0f, portraitOn = portraitOn, srcW = sw, srcH = sh)
        } else if (bufferW > 0) {
            crop = centerCrop(sw.toFloat() / sh, viewW.toFloat() / viewH)
            val portraitOn = portrait && mask?.hasPerson == true   // no person found: leave the picture sharp
            if (portraitOn || clarity != 0f) blurPass(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex, cameraTexMat(), mirror && !transformRotates)
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, viewW, viewH)
            val seed = (Math.random() * 1000).toFloat()
            draw(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex, cameraTexMat(), crop, viewW, viewH,
                flipY = false, mirror = mirror, seed = seed, portraitOn = portraitOn, srcW = sw, srcH = sh)
            encSurface?.let { encodeFrame(it, sw, sh, seed, portraitOn) }
        }
    }

    // ---- Video: the same picture, drawn a second time into the encoder's surface ----

    /** Starts drawing every camera frame into `surface` (a MediaCodec input surface) at w×h. */
    fun attachEncoder(surface: Surface, w: Int, h: Int): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        view.queueEvent {
            try {
                val d = EGL14.eglGetCurrentDisplay(); val ctx = EGL14.eglGetCurrentContext()
                check(ctx != EGL14.EGL_NO_CONTEXT) { "no GL context" }
                val id = IntArray(1); EGL14.eglQueryContext(d, ctx, EGL14.EGL_CONFIG_ID, id, 0)
                val cfg = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
                EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
                val s = EGL14.eglCreateWindowSurface(d, cfg[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(s != null && s != EGL14.EGL_NO_SURFACE) { "video surface error 0x%x".format(EGL14.eglGetError()) }
                encSurface = s; encW = w; encH = h
                done.complete(Unit)
            } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        return done
    }

    /** Stops drawing into the encoder; completes once no more frames will be sent. */
    fun detachEncoder(): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        view.queueEvent {
            encSurface?.let { runCatching { EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it) } }
            encSurface = null
            done.complete(Unit)
        }
        return done
    }

    private fun encodeFrame(enc: EGLSurface, sw: Int, sh: Int, seed: Float, portraitOn: Boolean) {
        val d = EGL14.eglGetCurrentDisplay(); val ctx = EGL14.eglGetCurrentContext()
        val drawS = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val readS = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(d, enc, enc, ctx)) return
        glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, encW, encH)
        // Saved videos aren't mirrored (like photos): the front camera's picture is flipped back on output.
        draw(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex, cameraTexMat(), centerCrop(sw.toFloat() / sh, encW.toFloat() / encH),
            encW, encH, flipY = false, mirror = mirror, seed = seed, portraitOn = portraitOn, srcW = sw, srcH = sh, flipOutput = mirror)
        EGLExt.eglPresentationTimeANDROID(d, enc, System.nanoTime())
        EGL14.eglSwapBuffers(d, enc)
        EGL14.eglMakeCurrent(d, drawS, readS, ctx)
    }

    /**
     * Renders a full-resolution upright photo with the current look into a new bitmap, cropped to `crop`.
     * Runs on the GL thread; the returned future completes when done.
     */
    fun renderPhoto(photo: Bitmap, crop: RectF, photoMask: Mask?, applyPortrait: Boolean): CompletableFuture<Bitmap> {
        val result = CompletableFuture<Bitmap>()
        view.queueEvent {
            try {
                syncTextures()
                var src = photo
                if (max(src.width, src.height) > maxTextureSize) {
                    val s = maxTextureSize.toFloat() / max(src.width, src.height)
                    src = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
                }
                val outW = max(1, (src.width * crop.width()).roundToInt()); val outH = max(1, (src.height * crop.height()).roundToInt())
                val ids = IntArray(2); glGenTextures(2, ids, 0)
                val srcTex = ids[0]; val outTex = ids[1]
                glBindTexture(GL_TEXTURE_2D, srcTex); linear(GL_TEXTURE_2D)
                GLUtils.texImage2D(GL_TEXTURE_2D, 0, src, 0)
                glBindTexture(GL_TEXTURE_2D, outTex); linear(GL_TEXTURE_2D)
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, outW, outH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
                val portraitOn = applyPortrait && photoMask?.hasPerson == true
                if (portraitOn) uploadMask(photoMask!!)
                if (portraitOn || clarity != 0f) blurPass(prog2d, GL_TEXTURE_2D, srcTex, identityTexMat(), mirrorImage = false)
                val fb = IntArray(1); glGenFramebuffers(1, fb, 0)
                glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outTex, 0)
                glViewport(0, 0, outW, outH)
                draw(prog2d, GL_TEXTURE_2D, srcTex, identityTexMat(), crop, outW, outH,
                    flipY = true, mirror = false, seed = 0f, portraitOn = portraitOn, srcW = src.width, srcH = src.height)
                val buf = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
                glPixelStorei(GL_PACK_ALIGNMENT, 1)
                glReadPixels(0, 0, outW, outH, GL_RGBA, GL_UNSIGNED_BYTE, buf)
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                glDeleteFramebuffers(1, fb, 0); glDeleteTextures(2, ids, 0)
                maskUploaded = -1   // the live mask must be re-uploaded
                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                buf.rewind(); out.copyPixelsFromBuffer(buf)
                result.complete(out)
            } catch (t: Throwable) { result.completeExceptionally(t) }
        }
        return result
    }

    // ---- Drawing ----

    private fun draw(prog: Int, target: Int, tex: Int, texMat: FloatArray, crop: RectF, outW: Int, outH: Int,
                     flipY: Boolean, mirror: Boolean, seed: Float, portraitOn: Boolean, srcW: Int, srcH: Int,
                     flipOutput: Boolean = false) {
        glUseProgram(prog)
        val aPos = glGetAttribLocation(prog, "aPos")
        glEnableVertexAttribArray(aPos); glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 0, quad)
        glActiveTexture(GL_TEXTURE0); glBindTexture(target, tex); glUniform1i(u(prog, "uImage"), 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_3D, lutTex); glUniform1i(u(prog, "uLut"), 1)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, maskTex); glUniform1i(u(prog, "uMask"), 2)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, bgTex); glUniform1i(u(prog, "uBg"), 3)
        glUniformMatrix4fv(u(prog, "uTexMat"), 1, false, texMat, 0)
        glUniform4f(u(prog, "uCrop"), crop.left, crop.top, crop.width(), crop.height())
        glUniform1i(u(prog, "uFlipY"), if (flipY) 1 else 0)
        glUniform1i(u(prog, "uFlipOut"), if (flipOutput) 1 else 0)
        // With a camera transform the stream is already mirrored for the front camera; the person mask never is.
        glUniform1i(u(prog, "uMirror"), if (mirror && !transformRotates) 1 else 0)
        glUniform1i(u(prog, "uMaskMirror"), if (mirror) 1 else 0)
        glUniform1i(u(prog, "uHasLut"), if (lut != null) 1 else 0)
        glUniform1i(u(prog, "uPortrait"), if (portraitOn) 1 else 0)
        // Blur radius ≈ 26 px per 1080 px of short edge at f/2, scaled by the simulated aperture.
        val rpx = 26f * (2f / max(aperture, 1f)) * (min(outW, outH) / 1080f)
        glUniform2f(u(prog, "uBlur"), rpx * crop.width() / outW, rpx * crop.height() / outH)
        glUniform1f(u(prog, "uGrain"), grain)
        glUniform1f(u(prog, "uGrainScale"), max(1f, min(outW, outH) / 1080f))
        glUniform1f(u(prog, "uSeed"), seed)
        // Sharpness works one output pixel apart; Clarity samples the small blurred copy (bgTex) around a
        // radius of 1.2 % of the picture's short side.
        glUniform2f(u(prog, "uPx"), crop.width() / outW, crop.height() / outH)
        glUniform1f(u(prog, "uSharp"), sharpness)
        glUniform1f(u(prog, "uClarity"), clarity)
        val r = 0.012f * min(srcW * crop.width(), srcH * crop.height())
        val rx = r / max(1, srcW); val ry = r / max(1, srcH)
        glUniform2f(u(prog, "uClarityR"), rx, ry)
        glUniform1f(u(prog, "uClarityLod"), (ln(max(1f, 448f * min(rx, ry) * 0.5f)) / ln(2f)))
        glUniform1i(u(prog, "uBlurPass"), 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Draws the whole source, small and plain, into bgTex (mipmapped) for the portrait blur.
     * It's sampled in normalized coordinates, so one fixed square size serves every photo shape.
     */
    private fun blurPass(prog: Int, target: Int, tex: Int, texMat: FloatArray, mirrorImage: Boolean) {
        val w = 448; val h = 448
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, bgTex)
        if (w != bgW || h != bgH) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            bgW = w; bgH = h
        }
        glBindFramebuffer(GL_FRAMEBUFFER, bgFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bgTex, 0)
        glViewport(0, 0, w, h)
        glUseProgram(prog)
        val aPos = glGetAttribLocation(prog, "aPos")
        glEnableVertexAttribArray(aPos); glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 0, quad)
        glActiveTexture(GL_TEXTURE0); glBindTexture(target, tex); glUniform1i(u(prog, "uImage"), 0)
        glUniformMatrix4fv(u(prog, "uTexMat"), 1, false, texMat, 0)
        glUniform4f(u(prog, "uCrop"), 0f, 0f, 1f, 1f)
        glUniform1i(u(prog, "uFlipY"), 1)
        glUniform1i(u(prog, "uFlipOut"), 0)
        glUniform1i(u(prog, "uMirror"), if (mirrorImage) 1 else 0)   // the blur copy is in display space
        glUniform1i(u(prog, "uBlurPass"), 1)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, bgTex); glGenerateMipmap(GL_TEXTURE_2D)
    }

    private fun syncTextures() {
        if (lutUploaded != lutVersion) {
            lut?.let { data ->
                glBindTexture(GL_TEXTURE_3D, lutTex)
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
                glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB8, LUT_SIZE, LUT_SIZE, LUT_SIZE, 0, GL_RGB, GL_UNSIGNED_BYTE, ByteBuffer.wrap(data))
            }
            lutUploaded = lutVersion
        }
        if (stillUploaded != stillVersion) {
            still?.let { glBindTexture(GL_TEXTURE_2D, stillTex); GLUtils.texImage2D(GL_TEXTURE_2D, 0, it, 0) }
            stillUploaded = stillVersion
        }
        if (maskUploaded != maskVersion) { mask?.let { uploadMask(it) }; maskUploaded = maskVersion }
    }

    private fun uploadMask(m: Mask) {
        glBindTexture(GL_TEXTURE_2D, maskTex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        m.data.rewind()
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, m.width, m.height, 0, GL_RED, GL_UNSIGNED_BYTE, m.data)
    }

    /** Upright (top-left origin) uv → camera texture coords: undo the sensor rotation, then the SurfaceTexture transform. */
    private fun cameraTexMat(): FloatArray {
        // Column-major 2D affine (s, t) = f(ux, uy) for each rotation; t is GL-style (0 = bottom of the buffer).
        val r = if (transformRotates) 0 else ((rotation % 360) + 360) % 360
        val rot = when (r) {
            90 -> floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)      // s = uy, t = ux
            180 -> floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)    // s = 1-ux, t = uy
            270 -> floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)   // s = 1-uy, t = 1-ux
            else -> floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)   // s = ux, t = 1-uy
        }
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, stMatrix, 0, rot, 0)
        return out
    }

    private fun identityTexMat() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private val uniforms = HashMap<Pair<Int, String>, Int>()
    private fun u(prog: Int, name: String) = uniforms.getOrPut(prog to name) { glGetUniformLocation(prog, name) }

    private fun linear(target: Int) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = glCreateShader(type).also {
            glShaderSource(it, src); glCompileShader(it)
            val ok = IntArray(1); glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + glGetShaderInfoLog(it) }
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs)); glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + glGetProgramInfoLog(p) }
        uniforms.keys.removeAll { it.first == p }
        return p
    }

    companion object {
        /** Centred part of a source (aspect `src` = w/h) that fills a view of aspect `view`. */
        fun centerCrop(src: Float, view: Float): RectF = when {
            kotlin.math.abs(src - view) < 0.002f -> RectF(0f, 0f, 1f, 1f)
            view < src -> { val w = view / src; RectF((1 - w) / 2, 0f, (1 + w) / 2, 1f) }
            else -> { val h = src / view; RectF(0f, (1 - h) / 2, 1f, (1 + h) / 2) }
        }

        private const val VERT = """#version 300 es
in vec2 aPos; out vec2 vUv;
uniform vec4 uCrop; uniform bool uFlipY, uFlipOut;
void main() {
  vec2 p = aPos * 0.5 + 0.5;
  vUv = uCrop.xy + vec2(p.x, uFlipY ? p.y : 1.0 - p.y) * uCrop.zw;   // upright, top-left origin
  gl_Position = vec4(uFlipOut ? -aPos.x : aPos.x, aPos.y, 0.0, 1.0);   // uFlipOut: mirror the output left-right
}"""

        private fun frag(oes: Boolean) = """#version 300 es
${if (oes) "#extension GL_OES_EGL_image_external_essl3 : require" else ""}
precision highp float; precision highp sampler3D;
in vec2 vUv; out vec4 outColor;
uniform ${if (oes) "samplerExternalOES" else "sampler2D"} uImage;
uniform mat4 uTexMat; uniform bool uMirror, uMaskMirror, uBlurPass;
uniform sampler3D uLut; uniform bool uHasLut;
uniform sampler2D uMask, uBg; uniform bool uPortrait; uniform vec2 uBlur;
uniform float uGrain, uGrainScale, uSeed;
uniform vec2 uPx, uClarityR; uniform float uSharp, uClarity, uClarityLod;
const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);
const vec2 DISK[16] = vec2[16](
  vec2(-0.613, 0.617), vec2(0.170, -0.040), vec2(-0.299, 0.791), vec2(0.645, 0.493),
  vec2(-0.651, 0.717), vec2(0.421, 0.027), vec2(-0.817, -0.271), vec2(-0.705, -0.668),
  vec2(0.977, -0.108), vec2(0.063, -0.997), vec2(0.185, 0.214), vec2(-0.433, -0.395),
  vec2(-0.200, 0.400), vec2(0.590, -0.640), vec2(0.910, 0.360), vec2(-0.050, 0.980));
vec3 img(vec2 uv) { return texture(uImage, (uTexMat * vec4(uv, 0.0, 1.0)).xy).rgb; }
float hash(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float valueNoise(vec2 p) {
  vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
bool maskMirror, mirrorImg;
vec3 src(vec2 uv) { return img(mirrorImg ? vec2(1.0 - uv.x, uv.y) : uv); }
float bgWeight(vec2 uv) { return 1.0 - smoothstep(0.35, 0.75, texture(uMask, maskMirror ? vec2(1.0 - uv.x, uv.y) : uv).r); }
void main() {
  // vUv: display space (upright, as shown). Image, mask and blur copy are sampled in their own spaces.
  vec2 uv = vUv;
  maskMirror = uMaskMirror; mirrorImg = uMirror;
  vec3 c = src(uv);
  if (uBlurPass) { outColor = vec4(c, 1.0); return; }
  if (uSharp != 0.0) {
    // Sharpness: detail = pixel minus its neighbours one output pixel away, added on luminance (+),
    // or blended towards the neighbours (−, softer).
    vec3 b = 0.25 * (src(uv + vec2(uPx.x, 0.0)) + src(uv - vec2(uPx.x, 0.0)) + src(uv + vec2(0.0, uPx.y)) + src(uv - vec2(0.0, uPx.y)));
    c = uSharp > 0.0 ? c + clamp(dot(c - b, LUM) * uSharp, -0.12, 0.12) : c + (c - b) * uSharp;
  }
  if (uClarity != 0.0) {
    // Clarity: local contrast against a wide blur, strongest in the midtones.
    vec3 b = textureLod(uBg, uv, uClarityLod).rgb;
    for (int i = 0; i < 8; i++) {
      float a = float(i) * 0.7854;
      b += textureLod(uBg, clamp(uv + vec2(cos(a), sin(a)) * uClarityR, 0.0, 1.0), uClarityLod).rgb;
    }
    float y = dot(c, LUM), d = y - dot(b / 9.0, LUM);
    c += uClarity * d * 4.0 * y * (1.0 - y);
  }
  if (uPortrait) {
    // Blur the background only, from a small mipmapped copy; people (mask) stay sharp and don't bleed.
    float bg = bgWeight(uv);
    if (bg > 0.01) {
      float a = hash(gl_FragCoord.xy) * 6.2832, ca = cos(a), sa = sin(a);
      vec3 acc = textureLod(uBg, uv, 1.0).rgb * 0.5; float ws = 0.5;
      for (int i = 0; i < 16; i++) {
        vec2 d = DISK[i]; d = vec2(d.x * ca - d.y * sa, d.x * sa + d.y * ca);
        vec2 p = clamp(uv + d * uBlur, 0.0, 1.0);
        float w = bgWeight(p);
        acc += textureLod(uBg, p, 1.0).rgb * w; ws += w;
      }
      c = mix(c, acc / ws, bg);
    }
  }
  if (uHasLut) {
    const float N = ${LUT_SIZE}.0;
    c = texture(uLut, clamp(c, 0.0, 1.0) * ((N - 1.0) / N) + 0.5 / N).rgb;
  }
  if (uGrain > 0.0) {
    vec2 p = gl_FragCoord.xy / uGrainScale + uSeed;
    float n = (valueNoise(p) + valueNoise(p * 1.9 + 17.0)) * 0.5 - 0.5;
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c += n * uGrain * 0.22 * (4.0 * y * (1.0 - y));
  }
  outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""
    }
}
