package app.fujifilmcam.filmcam

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.MediaStore
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class FlashSetting(val mode: Int) { Off(ImageCapture.FLASH_MODE_OFF), Auto(ImageCapture.FLASH_MODE_AUTO), On(ImageCapture.FLASH_MODE_ON) }
sealed interface CameraStatus {
    data object Idle : CameraStatus
    data object Running : CameraStatus
    data object Denied : CameraStatus
    data object Unavailable : CameraStatus
    data class Failed(val message: String) : CameraStatus
}

/** CameraX session, focus/exposure, zoom, portrait segmentation, capture and saving. */
class CameraController(private val activity: ComponentActivity, val glView: GLSurfaceView, val renderer: FilmRenderer) {
    var status by mutableStateOf<CameraStatus>(CameraStatus.Idle); private set
    var front by mutableStateOf(false); private set
    var zoomStops by mutableStateOf(listOf(1f)); private set
    var zoom by mutableStateOf(1f); private set
    var flash by mutableStateOf(FlashSetting.Off)
    var hasFlash by mutableStateOf(false); private set
    var capturing by mutableStateOf(false); private set
    var lastPhoto by mutableStateOf<Bitmap?>(null); private set
    var message by mutableStateOf<String?>(null)
    var showingStill by mutableStateOf(false); private set
    var focusPoint by mutableStateOf<Offset?>(null); private set     // viewfinder-normalized
    var focusLocked by mutableStateOf(false); private set
    var exposureBias by mutableStateOf(0f); private set
    var portrait by mutableStateOf(false); private set
    var personFound by mutableStateOf(false); private set               // portrait: ML Kit currently sees someone
    var flashCount by mutableStateOf(0); private set                   // bumps on each shot (shutter animation)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var stillFull: Bitmap? = null
    private var stillMask: Mask? = null
    private var captureRotation = Surface.ROTATION_0
    private val main = ContextCompat.getMainExecutor(activity)
    private val worker = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val streamSegmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build())
    }
    private val stillSegmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    }

    /** Photos follow how the phone is held, even though the screen stays in portrait. */
    private val orientation = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            val r = when (deg) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
            if (r != captureRotation) { captureRotation = r; capture?.targetRotation = r }
        }
    }

    init {
        renderer.onSurfaceTextureReady = { st -> main.execute { surfaceTexture = st; bind() } }
    }

    fun start() {
        orientation.enable()
        if (provider != null) { bind(); return }
        val f = ProcessCameraProvider.getInstance(activity)
        f.addListener({
            provider = runCatching { f.get() }.getOrElse { status = CameraStatus.Failed(it.message ?: "Camera error"); return@addListener }
            bind()
        }, main)
    }

    fun stop() { orientation.disable() }

    fun permissionDenied() { status = CameraStatus.Denied }

    private fun bind() {
        val p = provider ?: return
        val st = surfaceTexture ?: return
        p.unbindAll()
        var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) {
            selector = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) { status = CameraStatus.Unavailable; return }
            front = !front
        }
        val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
        val preview = Preview.Builder().setResolutionSelector(ratio).build()
        preview.setSurfaceProvider(main) { req ->
            val res = req.resolution
            st.setDefaultBufferSize(res.width, res.height)
            val surface = Surface(st)
            req.setTransformationInfoListener(main) { info ->
                renderer.setCameraBuffer(res.width, res.height, info.rotationDegrees, info.hasCameraTransform())
                glView.requestRender()
            }
            req.provideSurface(surface, main) { surface.release() }
        }
        val cap = ImageCapture.Builder()
            .setResolutionSelector(ratio)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flash.mode)
            .setTargetRotation(captureRotation)
            .build()
        capture = cap
        val useCases = mutableListOf(preview, cap)
        if (portrait) {
            val small = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            useCases += ImageAnalysis.Builder().setResolutionSelector(small)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                .also { it.setAnalyzer(analysisExecutor, ::segmentFrame) }
        }
        try {
            val cam = p.bindToLifecycle(activity, selector, *useCases.toTypedArray())
            camera = cam
            renderer.mirror = front
            hasFlash = cam.cameraInfo.hasFlashUnit()
            status = CameraStatus.Running
            focusPoint = null; focusLocked = false; exposureBias = 0f
            cam.cameraInfo.zoomState.observe(activity) { z ->
                zoom = z.zoomRatio
                val stops = mutableListOf<Float>()
                if (z.minZoomRatio < 0.95f) stops += z.minZoomRatio
                stops += 1f
                if (z.maxZoomRatio >= 2f) stops += 2f
                if (z.maxZoomRatio >= 5f) stops += 5f
                zoomStops = stops
            }
        } catch (e: Exception) {
            if (portrait) { portrait = false; renderer.portrait = false; message = "Portrait isn't supported with this camera."; bind(); return }
            status = CameraStatus.Failed(e.message ?: "Couldn't start the camera")
        }
    }

    fun flip() { front = !front; bind() }

    fun zoomTo(z: Float) { camera?.cameraControl?.setZoomRatio(z) }

    fun cycleFlash() {
        flash = FlashSetting.entries[(flash.ordinal + 1) % FlashSetting.entries.size]
        capture?.flashMode = flash.mode
    }

    fun enablePortrait(on: Boolean) {
        if (portrait == on) return
        portrait = on
        renderer.portrait = on
        if (!on) renderer.setMask(null)
        personFound = false
        bind()
    }

    // ---- Focus & exposure ----

    private fun viewToFrame(p: Offset): Offset {
        val c = renderer.crop
        return Offset(c.left + p.x * c.width(), c.top + p.y * c.height())
    }

    /** Tap: focus & meter at `p` (viewfinder-normalized). With `lock` it holds (AE/AF lock) until the next tap. */
    fun focus(p: Offset, lock: Boolean) {
        val cam = camera ?: return
        val f = viewToFrame(p)
        val display = activity.display ?: return
        // The display-oriented factory expects coordinates as shown on screen (mirrored for the front camera).
        val point = DisplayOrientedMeteringPointFactory(display, cam.cameraInfo, 1f, 1f).createPoint(f.x, f.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply { if (lock) disableAutoCancel() else setAutoCancelDuration(5, TimeUnit.SECONDS) }
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        setExposure(0f)
        focusPoint = p; focusLocked = lock
    }

    /** Exposure compensation in EV (±2, within what the camera supports). */
    fun setExposure(ev: Float) {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) { exposureBias = 0f; return }
        val step = st.exposureCompensationStep.toFloat()
        val range = st.exposureCompensationRange
        val idx = (ev.coerceIn(-2f, 2f) / step).roundToInt().coerceIn(range.lower, range.upper)
        if (idx != st.exposureCompensationIndex) cam.cameraControl.setExposureCompensationIndex(idx)
        exposureBias = idx * step
    }

    // ---- Portrait ----

    @OptIn(ExperimentalGetImage::class)
    private fun segmentFrame(proxy: ImageProxy) {
        if (showingStill) { proxy.close(); return }   // a library photo has its own mask
        val media = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        streamSegmenter.process(input)
            .addOnSuccessListener(analysisExecutor) { m ->
                if (!portrait) return@addOnSuccessListener
                val mask = toMask(m)
                renderer.setMask(mask); glView.requestRender()
                if (mask.hasPerson != personFound) main.execute { personFound = mask.hasPerson }
            }
            .addOnCompleteListener(analysisExecutor) { proxy.close() }
    }

    private fun toMask(m: SegmentationMask): Mask {
        val src = m.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = m.width * m.height
        val out = ByteBuffer.allocateDirect(n)
        var person = 0
        for (i in 0 until n) {
            val v = src.get(i).coerceIn(0f, 1f)
            if (v > 0.5f) person++
            out.put((v * 255f).toInt().toByte())
        }
        out.rewind()
        return Mask(out, m.width, m.height, hasPerson = person > n / 200)   // at least 0.5 % of the frame
    }

    // ---- Library photos ----

    fun openPhoto(uri: Uri) {
        worker.execute {
            try {
                val full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val long = max(info.size.width, info.size.height)
                    val limit = renderer.maxTextureSize
                    if (long > limit) d.setTargetSampleSize((long + limit - 1) / limit)
                }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                val s = min(1f, 2048f / max(full.width, full.height))
                val previewBmp = if (s < 1f) Bitmap.createScaledBitmap(full, (full.width * s).toInt(), (full.height * s).toInt(), true) else full
                // With Portrait on, library photos get the background blur too (preview and saved photo).
                val mask = if (portrait) segmentStill(full) else null
                main.execute {
                    stillFull = full; stillMask = mask; showingStill = true
                    renderer.setMask(mask); renderer.setStill(previewBmp)
                    if (portrait && mask?.hasPerson != true) message = "Portrait: no person found in this photo."
                }
            } catch (e: Exception) {
                main.execute { message = "Couldn't open that photo." }
            }
        }
    }

    fun closePhoto() { stillFull = null; stillMask = null; showingStill = false; renderer.setMask(null); renderer.setStill(null) }

    // ---- Capture ----

    fun shoot() {
        if (capturing) return
        val still = stillFull
        if (showingStill && still != null) {
            capturing = true
            val mask = stillMask; val blur = portrait
            worker.execute { finish(runCatching { renderer.renderPhoto(still, RectF(0f, 0f, 1f, 1f), mask, blur).get() }) }
            return
        }
        val cap = capture ?: return
        if (status != CameraStatus.Running) return
        capturing = true
        flashCount++
        val crop = RectF(renderer.crop)
        val wantPortrait = portrait
        cap.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching {
                    val bytes = image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rotation != 0) {
                        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    // The viewfinder crop is in portrait; a sideways photo uses the same crop turned sideways.
                    val c = if (bmp.width > bmp.height) RectF(crop.top, crop.left, crop.bottom, crop.right) else crop
                    val mask = if (wantPortrait) segmentStill(bmp) else null
                    renderer.renderPhoto(bmp, c, mask, wantPortrait).get()
                }
                finish(result)
            }
            override fun onError(e: ImageCaptureException) { finish(Result.failure(e)) }
        })
    }

    private fun segmentStill(bmp: Bitmap): Mask? = runCatching {
        val s = min(1f, 768f / max(bmp.width, bmp.height))
        val small = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), true)
        toMask(Tasks.await(stillSegmenter.process(InputImage.fromBitmap(small, 0))))
    }.getOrNull()

    private fun finish(result: Result<Bitmap>) {
        val saved = result.mapCatching { bmp -> save(bmp); Bitmap.createScaledBitmap(bmp, 240, max(1, 240 * bmp.height / bmp.width), true) }
        main.execute {
            capturing = false
            saved.onSuccess { lastPhoto = it }.onFailure { message = it.message?.let { m -> "Couldn't save the photo: $m" } ?: "Couldn't save the photo." }
        }
    }

    /** Saves to Pictures/FilmCam (shows up in Google Photos / the gallery). No permission needed on Android 10+. */
    private fun save(bmp: Bitmap) {
        val name = "FilmCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no storage")
        resolver.openOutputStream(uri)!!.use { out -> check(bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "encode" } }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    fun release() {
        worker.shutdown(); analysisExecutor.shutdown()
        runCatching { provider?.unbindAll() }
    }
}
