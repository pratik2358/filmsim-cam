package app.fujifilmcam.filmcam

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Size of every look LUT (33³ samples, RGB8, red varies fastest). */
const val LUT_SIZE = 33

/**
 * Fujifilm-style picture settings, baked together with the film look into one colour cube.
 * Order follows the camera: white balance → film look (+ strength) → tone curve → Color Chrome.
 * Line-for-line port of web/adjust.js (and FujiFilmCam/Engine/Adjustments.swift); keep them in sync.
 */
data class Adjustments(
    val highlight: Double = 0.0,   // −2 … +4, steps of 0.5 (Fujifilm "Highlight" tone)
    val shadow: Double = 0.0,      // −2 … +4, steps of 0.5 (Fujifilm "Shadow" tone)
    val kelvin: Double = 5500.0,   // 2500 … 10000; 5500 = as shot. Like Kelvin WB: lower = cooler picture
    val wbRed: Double = 0.0,       // −9 … +9 white-balance shift (red ↔ cyan)
    val wbBlue: Double = 0.0,      // −9 … +9 white-balance shift (blue ↔ yellow)
    val chrome: Int = 0,           // Color Chrome Effect: 0 off, 1 weak, 2 strong
    val chromeBlue: Int = 0,       // Color Chrome FX Blue: 0 off, 1 weak, 2 strong
) {
    val isNeutral get() = this == NEUTRAL

    /** Per-channel gains (linear light) for the white-balance settings, normalised to keep brightness. */
    fun wbGains(): DoubleArray {
        val g = doubleArrayOf(1.0, 1.0, 1.0)
        if (kelvin != 5500.0) {
            // Setting the camera to `kelvin` while the light is ~5500 K: neutralise a `kelvin` light.
            val ref = blackbody(5500.0); val w = blackbody(kelvin)
            for (i in 0..2) g[i] = ref[i] / w[i]
        }
        g[0] *= exp(0.03 * wbRed)
        g[2] *= exp(0.03 * wbBlue)
        val y = 0.2126 * g[0] + 0.7152 * g[1] + 0.0722 * g[2]
        return doubleArrayOf(g[0] / y, g[1] / y, g[2] / y)
    }

    /** Tone curve: smooth bumps centred at 75 % (highlights) and 25 % (shadows); + = harder, like Fujifilm. */
    private fun tone(c: DoubleArray) {
        if (highlight == 0.0 && shadow == 0.0) return
        val y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
        var d = 0.0
        if (y > 0.5 && y < 1) d += 0.025 * highlight * sin(2 * PI * (y - 0.5)).pow(2)
        if (y > 0 && y < 0.5) d -= 0.025 * shadow * sin(2 * PI * y).pow(2)
        for (i in 0..2) c[i] += d
    }

    /** Color Chrome Effect deepens strongly saturated colours; FX Blue does the same for blues (skies). */
    private fun colorChrome(c: DoubleArray) {
        if (chrome == 0 && chromeBlue == 0) return
        val r = c[0]; val g = c[1]; val b = c[2]
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx <= 0) return
        val sat = (mx - mn) / mx
        var k = 1.0
        if (chrome > 0) k -= CHROME[chrome] * smoothstep(0.25, 0.85, sat) * smoothstep(0.1, 0.6, mx)
        var boost = 0.0
        if (chromeBlue > 0 && mx > mn) {
            val d = mx - mn
            var h = if (mx == r) ((g - b) / d) % 6 else if (mx == g) (b - r) / d + 2 else (r - g) / d + 4
            h = (h * 60 + 360) % 360
            val w = (1 - smoothstep(25.0, 60.0, abs(h - 215))) * smoothstep(0.15, 0.6, sat)
            k -= CHROME_BLUE[chromeBlue] * w
            boost = 0.5 * CHROME_BLUE[chromeBlue] * w
        }
        for (i in 0..2) c[i] *= k
        if (boost != 0.0) {
            val y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
            for (i in 0..2) c[i] = y + (c[i] - y) * (1 + boost)
        }
    }

    /** Look + strength + these settings for one sRGB colour (in place). */
    fun apply(c: DoubleArray, gains: DoubleArray, look: ByteArray?, intensity: Double) {
        if (gains[0] != 1.0 || gains[1] != 1.0 || gains[2] != 1.0)
            for (i in 0..2) c[i] = clamp01(toSrgb(clamp01(toLin(c[i]) * gains[i])))
        if (look != null && intensity > 0) {
            val l = sampleLut(look, c[0], c[1], c[2])
            for (i in 0..2) c[i] += (l[i] - c[i]) * intensity
        }
        tone(c); colorChrome(c)
        for (i in 0..2) c[i] = clamp01(c[i])
    }

    /** Bakes look + strength + adjustments into an RGB8 33³ LUT (red fastest), as web/adjust.js bakeLUT. */
    fun bake(look: ByteArray?, intensity: Double): ByteArray {
        val out = ByteArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3)
        val gains = wbGains()
        val s = 1.0 / (LUT_SIZE - 1)
        val c = DoubleArray(3)
        var i = 0
        for (b in 0 until LUT_SIZE) for (g in 0 until LUT_SIZE) for (r in 0 until LUT_SIZE) {
            c[0] = r * s; c[1] = g * s; c[2] = b * s
            apply(c, gains, look, intensity)
            for (k in 0..2) out[i++] = (c[k] * 255).roundToInt().toByte()
        }
        return out
    }

    companion object {
        val NEUTRAL = Adjustments()
        private val CHROME = doubleArrayOf(0.0, 0.08, 0.15)
        private val CHROME_BLUE = doubleArrayOf(0.0, 0.10, 0.18)

        /** Linear-sRGB colour of a blackbody at `t` kelvin (Kim et al. Planckian-locus fit), Y = 1. */
        private fun blackbody(t0: Double): DoubleArray {
            val t = min(25000.0, max(1667.0, t0))
            val x = if (t <= 4000) -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
            else -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
            val y = when {
                t <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                t <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x.pow(3) - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
            }
            val bx = x / y; val bz = (1 - x - y) / y
            return doubleArrayOf(3.2406 * bx - 1.5372 - 0.4986 * bz, -0.9689 * bx + 1.8758 + 0.0415 * bz, 0.0557 * bx - 0.2040 + 1.0570 * bz)
        }

        private fun toLin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        private fun toSrgb(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    }
}

fun clamp01(v: Double) = if (v < 0) 0.0 else if (v > 1) 1.0 else v
private fun smoothstep(a: Double, b: Double, x: Double): Double { val t = clamp01((x - a) / (b - a)); return t * t * (3 - 2 * t) }

/** Trilinear sample of an RGB8 33³ LUT; returns 0…1 floats. */
fun sampleLut(data: ByteArray, r: Double, g: Double, b: Double): DoubleArray {
    val n = LUT_SIZE - 1
    fun f(v: Double) = min(n.toDouble(), max(0.0, v * n))
    val x = f(r); val y = f(g); val z = f(b)
    val x0 = x.toInt(); val y0 = y.toInt(); val z0 = z.toInt()
    val x1 = min(n, x0 + 1); val y1 = min(n, y0 + 1); val z1 = min(n, z0 + 1)
    val tx = x - x0; val ty = y - y0; val tz = z - z0
    fun at(i: Int, j: Int, k: Int, c: Int) = (data[((k * LUT_SIZE + j) * LUT_SIZE + i) * 3 + c].toInt() and 0xff).toDouble()
    val out = DoubleArray(3)
    for (c in 0..2) {
        val c00 = at(x0, y0, z0, c) * (1 - tx) + at(x1, y0, z0, c) * tx
        val c10 = at(x0, y1, z0, c) * (1 - tx) + at(x1, y1, z0, c) * tx
        val c01 = at(x0, y0, z1, c) * (1 - tx) + at(x1, y0, z1, c) * tx
        val c11 = at(x0, y1, z1, c) * (1 - tx) + at(x1, y1, z1, c) * tx
        out[c] = ((c00 * (1 - ty) + c10 * ty) * (1 - tz) + (c01 * (1 - ty) + c11 * ty) * tz) / 255
    }
    return out
}
