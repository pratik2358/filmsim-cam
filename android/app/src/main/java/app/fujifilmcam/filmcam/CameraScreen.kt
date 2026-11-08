package app.fujifilmcam.filmcam

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val Accent = Color(0xFFF5C83C)
private val RecordRed = Color(0xFFE5392F)
private enum class Panel { Looks, Adjust, Depth }

@Composable
fun CameraScreen(
    model: AppModel,
    camera: CameraController,
    glView: GLSurfaceView,
    onPickPhoto: () -> Unit,
    onImportLuts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var panel by remember { mutableStateOf<Panel?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var focusToken by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(camera.message) {
        camera.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); camera.message = null }
    }

    // No background here: the camera is a GL surface behind the window and shows through (the window itself is black).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bars = WindowInsets.systemBars.asPaddingValues()
        val top = bars.calculateTopPadding(); val bottom = bars.calculateBottomPadding()
        val still = camera.showingStill
        val aspect = if (still) 3f / 4f else model.ratio.aspect

        // ---- Viewfinder: as large as the screen allows; the bars float over it ----
        val (fx, fy, fw, fh) = if (aspect == null) listOf(0.dp, 0.dp, maxWidth, maxHeight) else {
            val safeH = maxHeight - top - bottom
            val w = minOf(maxWidth, safeH * aspect); val h = w / aspect
            listOf((maxWidth - w) / 2, top + minOf(44.dp, maxOf(0.dp, safeH - h)), w, h)
        }
        Box(Modifier.offset(fx, fy).size(fw, fh)) {
            AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
            if (model.grid) GridOverlay()
            FocusIndicator(camera, focusToken)
            StatusOverlay(camera, onOpenSettings, onPickPhoto)
            Viewfinder(
                enabled = camera.status == CameraStatus.Running && !still,
                panelOpen = panel != null,
                onClosePanel = { panel = null },
                onFocus = { p, lock -> camera.focus(p, lock); focusToken++ },
                onExposure = { ev -> camera.setExposure(ev); focusToken++ },
                exposure = { camera.exposureBias },
                onSwipe = { model.stepFilm(it) },
            )
            ShutterFlash(camera.flashCount)
        }

        // ---- Top bar and badges ----
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)))
                .padding(top = top)
        ) {
            TopBar(model, camera, onImportLuts = onImportLuts, onAbout = { showAbout = true })
            Hud(model, camera)
        }

        // ---- Bottom controls ----
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.25f to Color.Black.copy(alpha = if (panel == null) 0.25f else 0.6f),
                    1f to Color.Black.copy(alpha = if (panel == null) 0.35f else 0.72f),
                ))
                .padding(bottom = bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OverlayRow(camera)
            AnimatedVisibility(panel == Panel.Looks, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
                LooksPanel(model)
            }
            AnimatedVisibility(panel == Panel.Adjust, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
                AdjustPanel(model)
            }
            AnimatedVisibility(panel == Panel.Depth && camera.portrait && !still, enter = fadeIn(), exit = fadeOut()) {
                DepthPanel(model)
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelChip(model.film.name, open = panel == Panel.Looks) { panel = if (panel == Panel.Looks) null else Panel.Looks }
                PanelChip("Adjust", open = panel == Panel.Adjust, marked = !model.adjust.isNeutral) {
                    panel = if (panel == Panel.Adjust) null else Panel.Adjust
                }
                if (camera.portrait && !still) PanelChip("f/%.1f".format(model.aperture), open = panel == Panel.Depth) {
                    panel = if (panel == Panel.Depth) null else Panel.Depth
                }
            }
            if (!still) ModeSwitch(camera)
            ShutterRow(camera, onPickPhoto)
        }
    }

    if (showAbout) AboutDialog { showAbout = false }
}

// ---------------- Viewfinder gestures ----------------

/** One handler so gestures never fight: tap = focus/expose · hold = AE/AF lock · drag up/down = exposure · swipe sideways = change look. */
@Composable
private fun Viewfinder(
    enabled: Boolean, panelOpen: Boolean, onClosePanel: () -> Unit,
    onFocus: (Offset, Boolean) -> Unit, onExposure: (Float) -> Unit, exposure: () -> Float, onSwipe: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val state = rememberUpdatedState(listOf(enabled, panelOpen))
    val closePanel by rememberUpdatedState(onClosePanel)
    val focus by rememberUpdatedState(onFocus)
    val expose by rememberUpdatedState(onExposure)
    val swipe by rememberUpdatedState(onSwipe)
    val slop = with(density) { 12.dp.toPx() }; val swipeMin = with(density) { 40.dp.toPx() }; val perStop = with(density) { 110.dp.toPx() }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val (live, open) = state.value
            if (open) {   // a tap on the picture first closes the open panel
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                closePanel(); return@awaitEachGesture
            }
            val start = down.position
            val startEv = exposure()
            val norm = Offset((start.x / size.width).coerceIn(0f, 1f), (start.y / size.height).coerceIn(0f, 1f))
            var axis: Char? = null
            var held = false
            var last = start
            val hold: Job? = if (live) scope.launch {
                delay(550)
                if (axis == null) { held = true; focus(norm, true); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            } else null
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) { last = ch.position; break }
                last = ch.position
                val d = ch.position - start
                if (axis == null && hypot(d.x, d.y) > slop) { axis = if (abs(d.x) > abs(d.y)) 'h' else 'v'; hold?.cancel() }
                if (axis == 'v' && live) expose(startEv - d.y / perStop)
                ch.consume()
            }
            hold?.cancel()
            when (axis) {
                'h' -> { val dx = last.x - start.x; if (abs(dx) > swipeMin) swipe(if (dx < 0) 1 else -1) }
                null -> if (live && !held) focus(norm, false)
            }
        }
    })
}

@Composable
private fun FocusIndicator(camera: CameraController, token: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        visible = true
        delay(1500)
        if (!camera.focusLocked) visible = false
    }
    val alpha by animateFloatAsState(if (visible || camera.focusLocked) 1f else 0f, label = "focus")
    val p = camera.focusPoint ?: Offset(0.5f, 0.5f)
    if (alpha > 0f && !camera.showingStill) BoxWithConstraints(Modifier.fillMaxSize().alpha(alpha)) {
        val x = maxWidth * p.x; val y = maxHeight * p.y
        Box(Modifier.offset(x - 38.dp, y - 38.dp).size(76.dp).border(if (camera.focusLocked) 2.dp else 1.5.dp, Accent, RoundedCornerShape(3.dp)))
        Column(Modifier.offset(x + 48.dp, y - 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.WbSunny, null, tint = Accent, modifier = Modifier.size(18.dp))
            val ev = camera.exposureBias
            if (abs(ev) >= 0.05f) Text((if (ev > 0) "+" else "") + "%.1f".format(ev), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ShutterFlash(count: Int) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(count) { if (count > 0) { on = true; delay(60); on = false } }
    val a by animateFloatAsState(if (on) 0.7f else 0f, label = "shutter")
    if (a > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = a)))
}

@Composable
private fun GridOverlay() = Canvas(Modifier.fillMaxSize()) {
    val c = Color.White.copy(alpha = 0.35f)
    for (i in 1..2) {
        drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1f)
        drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f)
    }
}

@Composable
private fun StatusOverlay(camera: CameraController, onOpenSettings: () -> Unit, onPickPhoto: () -> Unit) {
    if (camera.showingStill) return
    val (title, detail) = when (val s = camera.status) {
        CameraStatus.Denied -> "Camera access is off" to "Allow camera access in Settings, or pick a photo to try the looks."
        CameraStatus.Unavailable -> "No camera available" to "This device has no camera. Pick a photo to preview the looks."
        is CameraStatus.Failed -> "Camera error" to s.message
        else -> return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(detail, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (camera.status == CameraStatus.Denied) Pill("Open Settings", onOpenSettings)
        Spacer(Modifier.height(8.dp))
        Pill("Pick a photo", onPickPhoto)
    }
}

// ---------------- Bars ----------------

@Composable
private fun TopBar(model: AppModel, camera: CameraController, onImportLuts: () -> Unit, onAbout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val still = camera.showingStill
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (camera.hasFlash && !still && camera.videoMode) {
            BarIcon(if (camera.torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, if (camera.torch) "Light on" else "Light off",
                if (camera.torch) Accent else Color.White) { camera.toggleTorch() }
        } else if (camera.hasFlash && !still) {
            val icon = when (camera.flash) { FlashSetting.Off -> Icons.Filled.FlashOff; FlashSetting.Auto -> Icons.Filled.FlashAuto; FlashSetting.On -> Icons.Filled.FlashOn }
            BarIcon(icon, "Flash ${camera.flash.name}", if (camera.flash == FlashSetting.Off) Color.White else Accent) { camera.cycleFlash() }
        }
        val ratioLocked = still || camera.recording
        Text(
            model.ratio.label, color = if (ratioLocked) Color.Gray else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.5.dp, if (ratioLocked) Color.Gray else Color.White, RoundedCornerShape(5.dp))
                .clickable(enabled = !ratioLocked) { model.updateRatio(model.ratio.next) }.widthIn(min = 40.dp).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        if (!still && camera.status == CameraStatus.Running) {
            val on = camera.portrait
            Row(Modifier.clip(RoundedCornerShape(8.dp)).alpha(if (camera.recording) 0.4f else 1f).clickable(enabled = !camera.recording) {
                model.updatePortrait(!on); camera.enablePortrait(!on)
            }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Portrait, null, tint = if (on) Accent else Color.White, modifier = Modifier.size(20.dp))
                Text(" Portrait", color = if (on) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            BarIcon(Icons.Filled.MoreVert, "More", Color.White) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf("Grain off", "Grain weak", "Grain strong").forEachIndexed { i, label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { model.updateGrain(i); menu = false },
                        leadingIcon = { if (model.grain == i) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Grid") }, onClick = { model.updateGrid(!model.grid); menu = false },
                    leadingIcon = { if (model.grid) Icon(Icons.Filled.Check, null) else Icon(Icons.Filled.GridOn, null) })
                DropdownMenuItem(text = { Text("Import LUT (.cube / Hald PNG)") }, onClick = { menu = false; onImportLuts() },
                    leadingIcon = { Icon(Icons.Filled.FileDownload, null) })
                DropdownMenuItem(text = { Text("About & credits") }, onClick = { menu = false; onAbout() },
                    leadingIcon = { Icon(Icons.Filled.Info, null) })
            }
        }
    }
}

@Composable
private fun Hud(model: AppModel, camera: CameraController) {
    var badge by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(model.filmId) {
        if (first) { first = false; return@LaunchedEffect }
        badge = true; delay(1400); badge = false
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (camera.recording) RecordingTimer(camera.recordStartedAt)
        if (camera.focusLocked && !camera.showingStill) {
            Text("AE/AF LOCK", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).background(Color(0xFFFFD60A), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        if (camera.portrait && !camera.personFound && !camera.showingStill && camera.status == CameraStatus.Running) {
            Text("Portrait: looking for a person…", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp).background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        AnimatedVisibility(badge, enter = fadeIn() + slideInVertically(), exit = fadeOut()) {
            Column(Modifier.padding(top = 10.dp).background(Color(0xCC141416), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(model.film.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(model.film.note, color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RecordingTimer(startedAt: Long) {
    var secs by remember { mutableIntStateOf(0) }
    LaunchedEffect(startedAt) { while (true) { secs = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt(); delay(250) } }
    Row(Modifier.padding(top = 8.dp).background(RecordRed, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(Color.White, CircleShape))
        Text("  %d:%02d".format(secs / 60, secs % 60), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** PHOTO · VIDEO, above the shutter. Asks for the microphone the first time Video is chosen. */
@Composable
private fun ModeSwitch(camera: CameraController) {
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Videos will be recorded without sound.", Toast.LENGTH_LONG).show()
    }
    val locked = camera.recording || camera.savingVideo
    Row(Modifier.padding(top = 10.dp).alpha(if (locked) 0.4f else 1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, video) in listOf("PHOTO" to false, "VIDEO" to true)) {
            val sel = camera.videoMode == video
            Text(label, color = if (sel) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
                    .clickable(enabled = !locked) {
                        camera.updateVideoMode(video)
                        if (video && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun OverlayRow(camera: CameraController) {
    if (camera.showingStill) {
        Row(Modifier.padding(bottom = 8.dp)) { Pill("Back to camera") { camera.closePhoto() } }
    } else if (camera.status == CameraStatus.Running && camera.zoomStops.size > 1) {
        Row(Modifier.padding(bottom = 8.dp).background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(50)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (z in camera.zoomStops) {
                val sel = abs(camera.zoom - z) < 0.05f
                val label = if (z < 1f) ".%d".format((z * 10).roundToInt()) else "%d".format(z.roundToInt())
                Text(label + if (sel) "×" else "", color = if (sel) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { camera.zoomTo(z) }
                        .wrapContentHeight())
            }
        }
    }
}

@Composable
private fun ShutterRow(camera: CameraController, onPickPhoto: () -> Unit) {
    val still = camera.showingStill
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).clickable(enabled = !camera.recording) { onPickPhoto() }, contentAlignment = Alignment.Center) {
            val thumb = camera.lastPhoto
            if (thumb != null) Image(thumb.asImageBitmap(), "Last photo; pick a photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Filled.PhotoLibrary, "Pick a photo", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        val context = LocalContext.current
        if (camera.videoMode && !still) {
            // Record / stop: a red dot that turns into a square while recording.
            val enabled = camera.status == CameraStatus.Running && !camera.savingVideo
            val inner by animateDpAsState(if (camera.recording) 30.dp else 62.dp, label = "rec")
            val corner by animateDpAsState(if (camera.recording) 6.dp else 31.dp, label = "recCorner")
            Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).alpha(if (enabled) 1f else 0.35f)
                .clip(CircleShape).clickable(enabled = enabled) {
                    if (camera.recording) camera.stopRecording()
                    else camera.startRecording(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(inner).background(RecordRed, RoundedCornerShape(corner)))
            }
        } else {
            val enabled = (camera.status == CameraStatus.Running || still) && !camera.capturing
            val pressScale by animateFloatAsState(if (camera.capturing) 0.85f else 1f, label = "shutter")
            Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).alpha(if (enabled || camera.capturing) 1f else 0.35f)
                .clip(CircleShape).clickable(enabled = enabled) { camera.shoot() }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().scale(pressScale).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    if (still) Text("Save", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(50.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = camera.status == CameraStatus.Running && !still && !camera.recording) { camera.flip() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Cameraswitch, "Switch camera", tint = if (still || camera.recording) Color.Gray else Color.White)
        }
    }
}

// ---------------- Panels ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LooksPanel(model: AppModel) {
    var deleting by remember { mutableStateOf<Film?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 20.dp).alpha(if (model.filmId == "original") 0.3f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Text("Strength", color = Color.Gray, fontSize = 12.sp)
            Slider(model.intensity, { model.updateIntensity(it) }, enabled = model.filmId != "original",
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp), colors = sliderColors())
            Text("${(model.intensity * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
        }
        val films = remember(model.libraryVersion) { model.library.all }
        val listState = rememberLazyListState()
        LaunchedEffect(Unit) {
            val i = films.indexOfFirst { it.id == model.filmId }
            if (i > 2) listState.scrollToItem(i - 2)
        }
        LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(films, key = { it.id }) { f ->
                val selected = f.id == model.filmId
                Column(Modifier.width(66.dp).combinedClickable(
                    onClick = { model.selectFilm(f.id) },
                    onLongClick = { if (f.group == "custom") deleting = f },
                ), horizontalAlignment = Alignment.CenterHorizontally) {
                    Swatch(model, f, selected)
                    Text(f.name, color = if (selected) Accent else Color.White, fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
    deleting?.let { f ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Remove “${f.name}”?") },
            confirmButton = { TextButton({ model.deleteLook(f.id); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Swatch(model: AppModel, f: Film, selected: Boolean) {
    val colors = remember(f.id, model.libraryVersion) { model.library.swatch(f.id) }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(58.dp).clip(shape).background(Color.White.copy(alpha = 0.08f))
        .border(if (selected) 2.5.dp else 1.dp, if (selected) Accent else Color.White.copy(alpha = 0.16f), shape), contentAlignment = Alignment.Center) {
        if (colors == null) Icon(Icons.Filled.Block, null, tint = Color.Gray)
        else Column(Modifier.fillMaxSize()) {
            for (row in 0..1) Row(Modifier.weight(1f)) {
                for (col in 0..2) {
                    val c = colors[row * 3 + col]
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(c[0].toFloat(), c[1].toFloat(), c[2].toFloat())))
                }
            }
        }
    }
}

@Composable
private fun AdjustPanel(model: AppModel) {
    val a = model.adjust
    fun signed(v: Double): String {
        val s = if (v == v.roundToInt().toDouble()) "${abs(v).roundToInt()}" else "%.1f".format(abs(v))
        return (if (v > 0) "+" else if (v < 0) "−" else "") + s
    }
    var tab by remember { mutableIntStateOf(0) }   // 0 Tone, 1 Color
    val toneChanged = a.highlight != 0.0 || a.shadow != 0.0 || a.clarity != 0 || a.sharpness != 0
    val colorChanged = a.copy(highlight = 0.0, shadow = 0.0, clarity = 0, sharpness = 0) != Adjustments.NEUTRAL
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AdjustTab("Tone", tab == 0, toneChanged) { tab = 0 }
            AdjustTab("Color", tab == 1, colorChanged) { tab = 1 }
            Spacer(Modifier.weight(1f))
            TextButton({ model.updateAdjust(Adjustments.NEUTRAL) }, enabled = !a.isNeutral) { Text("Reset", color = if (a.isNeutral) Color.Gray else Accent) }
        }
        if (tab == 0) {
            AdjustRow("Highlight", a.highlight, -2.0, 4.0, 0.5, 0.0, ::signed) { model.updateAdjust(a.copy(highlight = it)) }
            AdjustRow("Shadow", a.shadow, -2.0, 4.0, 0.5, 0.0, ::signed) { model.updateAdjust(a.copy(shadow = it)) }
            AdjustRow("Clarity", a.clarity.toDouble(), -5.0, 5.0, 1.0, 0.0, ::signed) { model.updateAdjust(a.copy(clarity = it.roundToInt())) }
            AdjustRow("Sharpness", a.sharpness.toDouble(), -4.0, 4.0, 1.0, 0.0, ::signed) { model.updateAdjust(a.copy(sharpness = it.roundToInt())) }
        } else {
            AdjustRow("Color", a.color.toDouble(), -4.0, 4.0, 1.0, 0.0, ::signed) { model.updateAdjust(a.copy(color = it.roundToInt())) }
            AdjustRow("Temperature", a.kelvin, 2500.0, 10000.0, 100.0, 5500.0, { if (it == 5500.0) "As shot" else "${it.roundToInt()}K" }) {
                model.updateAdjust(a.copy(kelvin = it))
            }
            AdjustRow("WB shift Red", a.wbRed, -9.0, 9.0, 1.0, 0.0, ::signed) { model.updateAdjust(a.copy(wbRed = it)) }
            AdjustRow("WB shift Blue", a.wbBlue, -9.0, 9.0, 1.0, 0.0, ::signed) { model.updateAdjust(a.copy(wbBlue = it)) }
            ChoiceRow("Color Chrome", a.chrome) { model.updateAdjust(a.copy(chrome = it)) }
            ChoiceRow("FX Blue", a.chromeBlue) { model.updateAdjust(a.copy(chromeBlue = it)) }
        }
    }
}

/** Tab in the Adjust panel; a dot marks a tab with changed settings. */
@Composable
private fun AdjustTab(title: String, selected: Boolean, changed: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (selected) Accent else Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (changed) Box(Modifier.padding(start = 5.dp).size(6.dp).background(Accent, CircleShape))
    }
}

@Composable
private fun AdjustRow(label: String, value: Double, lo: Double, hi: Double, step: Double, neutral: Double,
                      text: (Double) -> String, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Slider(value.toFloat(), { v ->
            val snapped = lo + ((v - lo) / step).roundToInt() * step
            if (snapped != value) onChange(snapped)
        }, valueRange = lo.toFloat()..hi.toFloat(), steps = ((hi - lo) / step).roundToInt() - 1,
            modifier = Modifier.weight(1f), colors = sliderColors(ticks = false))
        Text(text(value), color = if (value == neutral) Color.White else Accent, fontSize = 12.sp,
            modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun ChoiceRow(label: String, value: Int, options: List<String> = listOf("Off", "Weak", "Strong"), onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))) {
            options.forEachIndexed { i, s ->
                val sel = i == value
                Text(s, color = if (sel) Accent else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).background(if (sel) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onChange(i) }.padding(vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun DepthPanel(model: AppModel) {
    // Log scale so f/1.4–f/4 (where most of the change happens) gets most of the track.
    val t = (ln(16.0 / model.aperture) / ln(16.0 / 1.4)).toFloat()
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Depth", color = Color.Gray, fontSize = 12.sp)
        Slider(t, { v -> model.updateAperture(((16 * (1.4 / 16).pow(v.toDouble())) * 10).roundToInt() / 10f) },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp), colors = sliderColors())
        Text("f/%.1f".format(model.aperture), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

// ---------------- Small pieces ----------------

@Composable
private fun PanelChip(title: String, open: Boolean, marked: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (open) Accent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        if (marked) Box(Modifier.padding(start = 5.dp).size(6.dp).background(Accent, CircleShape))
        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, null,
            tint = if (open) Accent else Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun sliderColors(ticks: Boolean = true) = SliderDefaults.colors(
    thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f),
    activeTickColor = if (ticks) Color.Transparent else Color.Transparent, inactiveTickColor = Color.Transparent,
)

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Done") } },
        title = { Text("FilmCam") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Every look is a 3D colour lookup table applied on your phone's GPU to each camera frame, and to the full-resolution photo when you press the shutter. Nothing is uploaded.")
                Text("Camera looks", fontWeight = FontWeight.Bold)
                Text("Velvia, Astia, Classic Chrome, Classic Neg., Nostalgic Neg., Reala Ace, Pro Neg. Std/Hi, Eterna, Bleach Bypass and Acros are colour LUTs fitted to Fujifilm's published sample photos, measuring how each simulation changes colour relative to Provia. Provia is a mild built-in recipe. They capture colour and tone only and are approximations, not Fujifilm's own processing.")
                Text("Film stocks", fontWeight = FontWeight.Bold)
                Text("Adapted from the RawTherapee Film Simulation Collection (2015-09-20) by Pat David, Pavlov Dmitry and Michael Ezra, licensed CC BY-SA 4.0 (creativecommons.org/licenses/by-sa/4.0). The original Hald CLUTs were resampled to 33³ samples; the adapted LUTs are shared under the same licence.")
                Text("Portrait", fontWeight = FontWeight.Bold)
                Text("Portrait mode uses Google ML Kit selfie segmentation on the phone to find people and blur the background. It works best when a person is the subject.")
                Text("Your own LUTs", fontWeight = FontWeight.Bold)
                Text("Import a .cube file or a Hald CLUT .png from the ⋯ menu. Press and hold an imported look to remove it.")
                Text("Fujifilm and the film names are trademarks of FUJIFILM Corporation, used only to describe the look each preset aims for. This app is not affiliated with or endorsed by Fujifilm.",
                    color = Color.Gray, fontSize = 12.sp)
            }
        })
}

