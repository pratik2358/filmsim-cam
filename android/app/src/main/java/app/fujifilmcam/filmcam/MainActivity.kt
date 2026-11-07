package app.fujifilmcam.filmcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FilmRenderer
    private lateinit var camera: CameraController
    private lateinit var model: AppModel
    private val io = Executors.newSingleThreadExecutor()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.start() else camera.permissionDenied()
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) camera.openPhoto(uri)
    }
    private val pickLuts = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importLuts(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
        }
        renderer = FilmRenderer(glView)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        camera = CameraController(this, glView, renderer)
        model = AppModel(this, renderer)
        if (model.wantPortrait) camera.enablePortrait(true)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraScreen(
                    model = model, camera = camera, glView = glView,
                    onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onImportLuts = { pickLuts.launch(arrayOf("*/*")) },
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.start()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        camera.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        camera.release()
        super.onDestroy()
    }

    private fun importLuts(uris: List<Uri>) {
        if (uris.isEmpty()) return
        io.execute {
            val errors = mutableListOf<String>()
            var last: Film? = null
            for (uri in uris) {
                runCatching { model.library.import(uri) }
                    .onSuccess { last = it }
                    .onFailure { errors += (it.message ?: "not a valid LUT") }
            }
            runOnUiThread {
                last?.let { model.importedLook(it) }
                if (errors.isNotEmpty()) Toast.makeText(this, "Couldn't import: " + errors.joinToString(" · "), Toast.LENGTH_LONG).show()
            }
        }
    }
}
