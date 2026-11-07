package app.fujifilmcam.filmcam

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Android port must match web/adjust.js exactly (expected values computed with the web code). */
class AdjustmentsTest {
    private val cases = listOf(
        Adjustments(kelvin = 3200.0),
        Adjustments(kelvin = 8000.0, wbRed = 3.0, wbBlue = -5.0),
        Adjustments(highlight = 4.0, shadow = -2.0),
        Adjustments(chrome = 2, chromeBlue = 2),
        Adjustments(highlight = 1.5, shadow = 2.0, kelvin = 4200.0, wbRed = 2.0, chrome = 1, chromeBlue = 1),
    )
    private val colors = listOf(
        doubleArrayOf(0.85, 0.15, 0.12), doubleArrayOf(0.30, 0.55, 0.90), doubleArrayOf(0.87, 0.67, 0.55),
        doubleArrayOf(0.5, 0.5, 0.5), doubleArrayOf(0.1, 0.2, 0.05), doubleArrayOf(0.95, 0.9, 0.8),
    )
    private val expected = listOf(
        doubleArrayOf(0.676120, 0.148723, 0.193830),
        doubleArrayOf(0.231793, 0.546233, 1.000000),
        doubleArrayOf(0.692278, 0.665485, 0.805240),
        doubleArrayOf(0.393367, 0.496544, 0.734145),
        doubleArrayOf(0.070220, 0.198412, 0.094298),
        doubleArrayOf(0.756907, 0.894053, 1.000000),
        doubleArrayOf(0.950892, 0.146190, 0.085052),
        doubleArrayOf(0.339576, 0.538756, 0.709282),
        doubleArrayOf(0.973121, 0.656526, 0.429178),
        doubleArrayOf(0.561873, 0.489685, 0.389164),
        doubleArrayOf(0.117280, 0.195261, 0.029793),
        doubleArrayOf(1.000000, 0.882252, 0.629252),
        doubleArrayOf(0.895825, 0.195825, 0.165825),
        doubleArrayOf(0.301919, 0.551919, 0.901919),
        doubleArrayOf(0.961827, 0.761827, 0.641827),
        doubleArrayOf(0.500000, 0.500000, 0.500000),
        doubleArrayOf(0.137837, 0.237837, 0.087837),
        doubleArrayOf(0.982526, 0.932526, 0.832526),
        doubleArrayOf(0.722500, 0.127500, 0.102000),
        doubleArrayOf(0.196974, 0.388666, 0.657034),
        doubleArrayOf(0.856881, 0.659897, 0.541706),
        doubleArrayOf(0.500000, 0.500000, 0.500000),
        doubleArrayOf(0.098556, 0.197111, 0.049278),
        doubleArrayOf(0.950000, 0.900000, 0.800000),
        doubleArrayOf(0.685791, 0.094407, 0.092294),
        doubleArrayOf(0.220716, 0.458036, 0.849851),
        doubleArrayOf(0.845273, 0.703803, 0.679575),
        doubleArrayOf(0.462659, 0.497710, 0.585374),
        doubleArrayOf(0.052425, 0.161877, 0.028935),
        doubleArrayOf(0.899523, 0.913139, 0.948496)
    )

    @Test fun matchesWeb() {
        var i = 0
        for (a in cases) for (c in colors) {
            val o = c.copyOf()
            a.apply(o, a.wbGains(), null, 0.0)
            for (k in 0..2) assertEquals("case %d channel %d".format(i, k), expected[i][k], o[k], 1e-6)
            i++
        }
    }

    @Test fun neutralBakeIsIdentity() {
        val lut = Adjustments.NEUTRAL.bake(null, 0.0)
        val s = 1.0 / (LUT_SIZE - 1)
        var i = 0
        for (b in 0 until LUT_SIZE) for (g in 0 until LUT_SIZE) for (r in 0 until LUT_SIZE) {
            for (v in doubleArrayOf(r * s, g * s, b * s)) assertEquals(Math.round(v * 255).toInt(), lut[i++].toInt() and 0xff)
        }
    }

    @Test fun cubeImportResamplesTo33() {
        // A 2×2×2 identity .cube (with a comment and a title) must come out as the 33³ identity.
        val cube = """
            # identity
            TITLE "id"
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()
        val lut = LookLibrary.parseCube(cube)
        assertEquals(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3, lut.size)
        val s = 1.0 / (LUT_SIZE - 1)
        var i = 0
        for (b in 0 until LUT_SIZE) for (g in 0 until LUT_SIZE) for (r in 0 until LUT_SIZE)
            for (v in doubleArrayOf(r * s, g * s, b * s)) assertEquals(Math.round(v * 255).toInt(), lut[i++].toInt() and 0xff)
    }
}
