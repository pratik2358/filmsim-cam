package app.fujifilmcam.filmcam

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class FrameRatio(val label: String, val aspect: Float?) {
    Standard("3:4", 3f / 4f), Tall("9:16", 9f / 16f), Full("Full", null);
    val next get() = entries[(ordinal + 1) % entries.size]
}

/** User settings (remembered between launches) and the look that the renderer draws with. */
class AppModel(context: Context, private val renderer: FilmRenderer) {
    private val prefs = context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
    val library = LookLibrary(context)

    var filmId by mutableStateOf(prefs.getString("filmId", "classic-chrome")!!); private set
    var intensity by mutableStateOf(prefs.getFloat("intensity", 1f)); private set
    var grain by mutableStateOf(prefs.getInt("grain", 0)); private set          // 0 off, 1 weak, 2 strong
    var grid by mutableStateOf(prefs.getBoolean("grid", false)); private set
    var ratio by mutableStateOf(FrameRatio.entries.getOrElse(prefs.getInt("ratio", 2)) { FrameRatio.Full }); private set
    var wantPortrait by mutableStateOf(prefs.getBoolean("portrait", false)); private set
    var aperture by mutableStateOf(prefs.getFloat("aperture", 2.8f)); private set
    var adjust by mutableStateOf(loadAdjust()); private set
    /** Bumps when imported looks change, so the strip redraws. */
    var libraryVersion by mutableStateOf(0); private set

    val film: Film get() = library.film(filmId) ?: BUILT_IN_FILMS[0]

    fun selectFilm(id: String) { filmId = if (library.film(id) != null) id else "original"; prefs.edit().putString("filmId", filmId).apply(); applyLook() }
    fun stepFilm(delta: Int) {
        val all = library.all; val i = all.indexOfFirst { it.id == filmId }.coerceAtLeast(0)
        selectFilm(all[(i + delta + all.size) % all.size].id)
    }
    fun updateIntensity(v: Float) { intensity = v; prefs.edit().putFloat("intensity", v).apply(); applyLook() }
    fun updateGrain(v: Int) { grain = v; prefs.edit().putInt("grain", v).apply(); renderer.grain = GRAIN[v] }
    fun updateGrid(v: Boolean) { grid = v; prefs.edit().putBoolean("grid", v).apply() }
    fun updateRatio(v: FrameRatio) { ratio = v; prefs.edit().putInt("ratio", v.ordinal).apply() }
    fun updatePortrait(v: Boolean) { wantPortrait = v; prefs.edit().putBoolean("portrait", v).apply() }
    fun updateAperture(v: Float) { aperture = v; prefs.edit().putFloat("aperture", v).apply(); renderer.aperture = v }
    fun updateAdjust(a: Adjustments) {
        adjust = a
        prefs.edit().putFloat("adj.highlight", a.highlight.toFloat()).putFloat("adj.shadow", a.shadow.toFloat())
            .putFloat("adj.kelvin", a.kelvin.toFloat()).putFloat("adj.wbRed", a.wbRed.toFloat()).putFloat("adj.wbBlue", a.wbBlue.toFloat())
            .putInt("adj.chrome", a.chrome).putInt("adj.chromeBlue", a.chromeBlue)
            .putInt("adj.color", a.color).putInt("adj.sharpness", a.sharpness).putInt("adj.clarity", a.clarity).apply()
        applyShaderSettings()
        applyLook()
    }

    fun deleteLook(id: String) {
        library.delete(id); libraryVersion++
        if (filmId == id) selectFilm("original")
    }
    fun importedLook(f: Film) { libraryVersion++; selectFilm(f.id) }

    private fun loadAdjust() = Adjustments(
        prefs.getFloat("adj.highlight", 0f).toDouble(), prefs.getFloat("adj.shadow", 0f).toDouble(),
        prefs.getFloat("adj.kelvin", 5500f).toDouble(), prefs.getFloat("adj.wbRed", 0f).toDouble(),
        prefs.getFloat("adj.wbBlue", 0f).toDouble(), prefs.getInt("adj.chrome", 0), prefs.getInt("adj.chromeBlue", 0),
        prefs.getInt("adj.color", 0), prefs.getInt("adj.sharpness", 0), prefs.getInt("adj.clarity", 0),
    )

    private fun applyShaderSettings() {
        renderer.sharpness = adjust.sharpAmount
        renderer.clarity = adjust.clarityAmount
        renderer.requestRender()
    }

    // Look + strength + picture settings are baked into one LUT off the main thread; only the newest request wins.
    private val baker = Executors.newSingleThreadExecutor()
    private val latest = AtomicInteger()

    fun applyLook() {
        val id = filmId; val t = intensity.toDouble(); val a = adjust
        val req = latest.incrementAndGet()
        baker.execute {
            if (latest.get() != req) return@execute
            val look = if (t > 0.001) library.lut(id) else null
            val data = if (look == null && a.isNeutralLut) null else a.bake(look, t)
            if (latest.get() == req) renderer.setLut(data)
        }
    }

    init {
        renderer.grain = GRAIN[grain]
        renderer.aperture = aperture
        applyShaderSettings()
        applyLook()
    }

    companion object { val GRAIN = floatArrayOf(0f, 0.5f, 1f) }
}
