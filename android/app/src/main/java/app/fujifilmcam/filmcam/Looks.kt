package app.fujifilmcam.filmcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Film(val id: String, val name: String, val note: String, val group: String)

/** Same catalogue as the website (web/app.js FILMS) and the iOS app. */
val BUILT_IN_FILMS = listOf(
    Film("original", "Original", "No look applied", "camera"),
    Film("provia", "Provia", "Standard, faithful colour", "camera"),
    Film("velvia", "Velvia", "Vivid, deep landscapes", "camera"),
    Film("astia", "Astia", "Soft, flattering skin", "camera"),
    Film("classic-chrome", "Classic Chrome", "Muted documentary tones", "camera"),
    Film("classic-neg", "Classic Neg.", "Hard tone, cyan shadows", "camera"),
    Film("nostalgic-neg", "Nostalgic Neg.", "Amber highlights, warm", "camera"),
    Film("reala-ace", "Reala Ace", "Natural, true-to-life", "camera"),
    Film("pro-neg-std", "Pro Neg. Std", "Soft portrait tonality", "camera"),
    Film("pro-neg-hi", "Pro Neg. Hi", "Punchier portraits", "camera"),
    Film("eterna", "Eterna", "Cinematic, understated", "camera"),
    Film("eterna-bleach", "Bleach Bypass", "Desaturated, high contrast", "camera"),
    Film("acros", "Acros", "Fine black & white", "camera"),
    Film("s-provia-100f", "Provia 100F", "Slide film", "stock"),
    Film("s-velvia-50", "Velvia 50", "Slide film", "stock"),
    Film("s-velvia-100", "Velvia 100", "Slide film", "stock"),
    Film("s-astia-100f", "Astia 100F", "Slide film", "stock"),
    Film("s-sensia-100", "Sensia 100", "Slide film", "stock"),
    Film("s-pro-160c", "Pro 160C", "Portrait negative", "stock"),
    Film("s-pro-400h", "Pro 400H", "Portrait negative", "stock"),
    Film("s-pro-800z", "Pro 800Z", "Portrait negative", "stock"),
    Film("s-superia-200", "Superia 200", "Consumer negative", "stock"),
    Film("s-superia-400", "Superia 400", "Consumer negative", "stock"),
    Film("s-reala-100", "Superia Reala 100", "Consumer negative", "stock"),
    Film("s-xtra-800", "Superia X-Tra 800", "Consumer negative", "stock"),
    Film("s-fp-100c", "FP-100C", "Instant peel-apart", "stock"),
    Film("s-acros-100", "Neopan Acros 100", "B&W negative", "stock"),
    Film("s-neopan-1600", "Neopan 1600", "B&W negative", "stock"),
)
val GROUP_NAMES = mapOf("camera" to "Camera looks", "stock" to "Film stocks", "custom" to "Imported")

/** Skin, sky, foliage, red, dark grey, light grey (sRGB 0…1) for the look swatches. */
val SWATCH_REFS = listOf(
    doubleArrayOf(.87, .67, .55), doubleArrayOf(.45, .66, .90), doubleArrayOf(.33, .55, .24),
    doubleArrayOf(.80, .18, .16), doubleArrayOf(.25, .25, .25), doubleArrayOf(.85, .85, .85),
)

/** Built-in LUTs come from assets/luts/<id>.bin; imported ones live in filesDir/luts/<id>.bin. */
class LookLibrary(private val context: Context) {
    private val cache = HashMap<String, ByteArray>()
    private val dir = File(context.filesDir, "luts").apply { mkdirs() }
    private val metaFile = File(dir, "imported.json")

    var custom: List<Film> = loadMeta(); private set
    val all: List<Film> get() = BUILT_IN_FILMS + custom

    fun film(id: String) = all.firstOrNull { it.id == id }

    @Synchronized
    fun lut(id: String): ByteArray? {
        if (id == "original") return null
        cache[id]?.let { return it }
        val data = runCatching {
            if (id.startsWith("u-")) File(dir, "$id.bin").readBytes()
            else context.assets.open("luts/$id.bin").use { it.readBytes() }
        }.getOrNull()?.takeIf { it.size == LUT_SIZE * LUT_SIZE * LUT_SIZE * 3 }
        if (data != null) cache[id] = data
        return data
    }

    /** Imports a .cube or Hald CLUT image. Returns the new look, or throws with a readable message. */
    fun import(uri: Uri): Film {
        val name = displayName(uri) ?: "Imported LUT"
        val ext = name.substringAfterLast('.', "").lowercase()
        val data = if (ext == "cube") {
            parseCube(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } else {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.setUnpremultipliedRequired(true)
            }
            parseHald(bmp)
        }
        val id = "u-" + System.currentTimeMillis().toString(36) + (1000..9999).random()
        File(dir, "$id.bin").writeBytes(data)
        cache[id] = data
        val film = Film(id, name.substringBeforeLast('.'), "Imported LUT", "custom")
        custom = custom + film
        saveMeta()
        return film
    }

    fun delete(id: String) {
        File(dir, "$id.bin").delete()
        cache.remove(id)
        custom = custom.filterNot { it.id == id }
        saveMeta()
    }

    /** Six representative colours through the look, for the strip. */
    fun swatch(id: String): List<DoubleArray>? = lut(id)?.let { l -> SWATCH_REFS.map { sampleLut(l, it[0], it[1], it[2]) } }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun loadMeta(): List<Film> = runCatching {
        val arr = JSONArray(metaFile.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Film(o.getString("id"), o.getString("name"), "Imported LUT", "custom") } }
    }.getOrDefault(emptyList())

    private fun saveMeta() {
        val arr = JSONArray()
        custom.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        metaFile.writeText(arr.toString())
    }

    companion object {
        /** Resamples a float LUT (RGB, red fastest) of any size to 33³ RGB8. */
        fun toStandard(values: FloatArray, size: Int): ByteArray {
            val out = ByteArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3)
            val n = size - 1
            fun at(i: Int, j: Int, k: Int, c: Int) = values[((k * size + j) * size + i) * 3 + c]
            var o = 0
            for (b in 0 until LUT_SIZE) for (g in 0 until LUT_SIZE) for (r in 0 until LUT_SIZE) {
                val x = r.toFloat() / (LUT_SIZE - 1) * n; val y = g.toFloat() / (LUT_SIZE - 1) * n; val z = b.toFloat() / (LUT_SIZE - 1) * n
                val x0 = x.toInt(); val y0 = y.toInt(); val z0 = z.toInt()
                val x1 = min(n, x0 + 1); val y1 = min(n, y0 + 1); val z1 = min(n, z0 + 1)
                val tx = x - x0; val ty = y - y0; val tz = z - z0
                for (c in 0..2) {
                    val c00 = at(x0, y0, z0, c) * (1 - tx) + at(x1, y0, z0, c) * tx
                    val c10 = at(x0, y1, z0, c) * (1 - tx) + at(x1, y1, z0, c) * tx
                    val c01 = at(x0, y0, z1, c) * (1 - tx) + at(x1, y0, z1, c) * tx
                    val c11 = at(x0, y1, z1, c) * (1 - tx) + at(x1, y1, z1, c) * tx
                    val v = (c00 * (1 - ty) + c10 * ty) * (1 - tz) + (c01 * (1 - ty) + c11 * ty) * tz
                    out[o++] = (min(1f, max(0f, v)) * 255).roundToInt().toByte()
                }
            }
            return out
        }

        fun parseCube(text: String): ByteArray {
            var size = 0
            var lo = floatArrayOf(0f, 0f, 0f); var hi = floatArrayOf(1f, 1f, 1f)
            val vals = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#') continue
                val p = line.split(Regex("\\s+"))
                when (p[0].uppercase()) {
                    "LUT_3D_SIZE" -> size = p[1].toInt()
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported")
                    "DOMAIN_MIN" -> lo = FloatArray(3) { p[it + 1].toFloat() }
                    "DOMAIN_MAX" -> hi = FloatArray(3) { p[it + 1].toFloat() }
                    else -> if (p.size >= 3 && p[0].toFloatOrNull() != null)
                        for (c in 0..2) vals.add((p[c].toFloat() - lo[c]) / (hi[c] - lo[c]))
                }
            }
            require(size >= 2) { "missing LUT_3D_SIZE" }
            require(vals.size == size * size * size * 3) { "expected ${size * size * size} entries, found ${vals.size / 3}" }
            return toStandard(vals.toFloatArray(), size)
        }

        fun parseHald(bmp: Bitmap): ByteArray {
            val w = bmp.width
            val level = cbrt(w.toDouble()).roundToInt()
            require(bmp.height == w && level * level * level == w) { "image must be a square Hald CLUT (e.g. 512×512 or 1728×1728)" }
            val px = IntArray(w * w).also { bmp.getPixels(it, 0, w, 0, 0, w, w) }
            val size = level * level
            val vals = FloatArray(size * size * size * 3)
            for (i in px.indices) {
                vals[i * 3] = ((px[i] shr 16) and 0xff) / 255f
                vals[i * 3 + 1] = ((px[i] shr 8) and 0xff) / 255f
                vals[i * 3 + 2] = (px[i] and 0xff) / 255f
            }
            return toStandard(vals, size)
        }
    }
}
