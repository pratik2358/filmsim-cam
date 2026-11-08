import Foundation
import simd

/// Fujifilm-style picture settings, baked together with the film look into one colour cube.
/// Order follows the camera: white balance → film look (+ strength) → tone curve → Color Chrome → Color.
/// Dynamic range, Sharpness and Clarity aren't colour mappings; FilmRenderer applies them separately.
/// This is a line-for-line port of web/adjust.js; keep the two in sync.
struct Adjustments: Codable, Equatable, Sendable {
    var highlight: Double = 0    // −2 … +4, steps of 0.5 (Fujifilm "Highlight" tone)
    var shadow: Double = 0       // −2 … +4, steps of 0.5 (Fujifilm "Shadow" tone)
    var kelvin: Double = 5500    // 2500 … 10000; 5500 = as shot. Like Kelvin WB: lower = cooler picture
    var wbRed: Double = 0        // −9 … +9 white-balance shift (red ↔ cyan)
    var wbBlue: Double = 0       // −9 … +9 white-balance shift (blue ↔ yellow)
    var chrome: Int = 0          // Color Chrome Effect: 0 off, 1 weak, 2 strong
    var chromeBlue: Int = 0      // Color Chrome FX Blue: 0 off, 1 weak, 2 strong
    var color: Int = 0           // −4 … +4 saturation (Fujifilm "Color")
    var dr: Int = 100            // Dynamic range: 100, 200 or 400 (%)
    var sharpness: Int = 0       // −4 … +4
    var clarity: Int = 0         // −5 … +5 (midtone local contrast)

    static let neutral = Adjustments()
    var isNeutral: Bool { self == .neutral }
    /// True when the colour cube is unaffected (only DR / sharpness / clarity changed).
    var isNeutralCube: Bool { var a = self; a.dr = 100; a.sharpness = 0; a.clarity = 0; return a == .neutral }
    /// Stops the camera underexposes for DR200 / DR400, like Fujifilm cameras.
    var drStops: Int { dr == 400 ? 2 : dr == 200 ? 1 : 0 }
    /// Same amounts as web/adjust.js: unsharp mask (+) or soften (−); midtone local contrast.
    var sharpAmount: Double { sharpness > 0 ? 0.25 * Double(sharpness) : 0.15 * Double(sharpness) }
    var clarityAmount: Double { 0.12 * Double(clarity) }

    /// Dynamic range: linear gain and highlight-shoulder knee (1 = none). `underexposedEV` is how far the camera was
    /// actually underexposed for DR (0 for library photos): the picture is brought back up and the highlights it kept
    /// roll off smoothly. Without underexposure DR can only soften highlights. As web/adjust.js drShader.
    func drShader(underexposedEV: Double) -> (gain: Double, knee: Double) {
        let n = drStops
        if n == 0 { return (1, 1) }
        if underexposedEV > 0 { return (pow(2, underexposedEV), 0.55) }
        return (1, n == 1 ? 0.7 : 0.5)
    }

    /// Colour-cube data (RGBA float32, red fastest) for the dynamic-range gain and shoulder, in sRGB.
    static func drCubeData(gain: Double, knee k: Double, size: Int = 33) -> Data {
        var rgba = [Float](repeating: 1, count: size * size * size * 4)
        let s = 1 / Double(size - 1)
        var i = 0
        for b in 0..<size {
            for g in 0..<size {
                for r in 0..<size {
                    var l = SIMD3(toLin(Double(r) * s), toLin(Double(g) * s), toLin(Double(b) * s)) * gain
                    let m = max(l.x, l.y, l.z)
                    if k < 1, m > k { l *= (k + (1 - k) * (1 - exp(-(m - k) / (1 - k)))) / m }
                    rgba[i] = Float(toSrgb(clamp01(l.x))); rgba[i + 1] = Float(toSrgb(clamp01(l.y))); rgba[i + 2] = Float(toSrgb(clamp01(l.z)))
                    i += 4
                }
            }
        }
        return rgba.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    // MARK: White balance

    /// Linear-sRGB colour of a blackbody at `t` kelvin (Kim et al. Planckian-locus fit), Y = 1.
    private static func blackbody(_ t0: Double) -> SIMD3<Double> {
        let t = min(25000, max(1667, t0))
        let x = t <= 4000
            ? -0.2661239e9 / pow(t, 3) - 0.2343589e6 / pow(t, 2) + 0.8776956e3 / t + 0.179910
            : -3.0258469e9 / pow(t, 3) + 2.1070379e6 / pow(t, 2) + 0.2226347e3 / t + 0.240390
        let y = t <= 2222 ? -1.1063814 * pow(x, 3) - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            : t <= 4000 ? -0.9549476 * pow(x, 3) - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            : 3.0817580 * pow(x, 3) - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        let X = x / y, Z = (1 - x - y) / y
        return SIMD3(3.2406 * X - 1.5372 - 0.4986 * Z, -0.9689 * X + 1.8758 + 0.0415 * Z, 0.0557 * X - 0.2040 + 1.0570 * Z)
    }

    /// Per-channel gains (linear light) for the white-balance settings, normalised to keep brightness.
    var wbGains: SIMD3<Double> {
        var g = SIMD3<Double>(1, 1, 1)
        if kelvin != 5500 {
            // Setting the camera to `kelvin` while the light is ~5500 K: neutralise a `kelvin` light.
            g = Self.blackbody(5500) / Self.blackbody(kelvin)
        }
        g.x *= exp(0.03 * wbRed)
        g.z *= exp(0.03 * wbBlue)
        return g / (0.2126 * g.x + 0.7152 * g.y + 0.0722 * g.z)
    }

    // MARK: Tone & Color Chrome

    /// Tone curve: smooth bumps centred at 75 % (highlights) and 25 % (shadows); + = harder, like Fujifilm.
    private func tone(_ c: SIMD3<Double>) -> SIMD3<Double> {
        if highlight == 0 && shadow == 0 { return c }
        let y = 0.2126 * c.x + 0.7152 * c.y + 0.0722 * c.z
        var d = 0.0
        if y > 0.5 && y < 1 { d += 0.025 * highlight * pow(sin(2 * .pi * (y - 0.5)), 2) }
        if y > 0 && y < 0.5 { d -= 0.025 * shadow * pow(sin(2 * .pi * y), 2) }
        return c + d
    }

    private static let chromeAmount: [Double] = [0, 0.08, 0.15], chromeBlueAmount: [Double] = [0, 0.10, 0.18]

    /// Color Chrome Effect deepens strongly saturated colours; FX Blue does the same for blues (skies).
    private func colorChrome(_ c: SIMD3<Double>) -> SIMD3<Double> {
        if chrome == 0 && chromeBlue == 0 { return c }
        let (r, g, b) = (c.x, c.y, c.z)
        let mx = max(r, g, b), mn = min(r, g, b)
        guard mx > 0 else { return c }
        let sat = (mx - mn) / mx
        var k = 1.0
        if chrome > 0 { k -= Self.chromeAmount[chrome] * smoothstep(0.25, 0.85, sat) * smoothstep(0.1, 0.6, mx) }
        var boost = 0.0
        if chromeBlue > 0 && mx > mn {
            let d = mx - mn
            var h = mx == r ? fmod((g - b) / d, 6) : mx == g ? (b - r) / d + 2 : (r - g) / d + 4
            h = fmod(h * 60 + 360, 360)
            let w = (1 - smoothstep(25, 60, abs(h - 215))) * smoothstep(0.15, 0.6, sat)
            k -= Self.chromeBlueAmount[chromeBlue] * w
            boost = 0.5 * Self.chromeBlueAmount[chromeBlue] * w
        }
        var o = c * k
        if boost != 0 {
            let y = 0.2126 * o.x + 0.7152 * o.y + 0.0722 * o.z
            o = y + (o - y) * (1 + boost)
        }
        return o
    }

    // MARK: Baking

    /// Look + strength + these settings for one sRGB colour.
    func apply(_ c0: SIMD3<Double>, gains: SIMD3<Double>, look: LUT?, intensity: Double) -> SIMD3<Double> {
        var c = c0
        if gains != SIMD3(1, 1, 1) {
            c = SIMD3(Self.toSrgb(clamp01(Self.toLin(c.x) * gains.x)),
                      Self.toSrgb(clamp01(Self.toLin(c.y) * gains.y)),
                      Self.toSrgb(clamp01(Self.toLin(c.z) * gains.z)))
            c = simd_clamp(c, .zero, .one)
        }
        if let look, intensity > 0 {
            let l = SIMD3<Double>(look.sample(SIMD3<Float>(c)))
            c += (l - c) * intensity
        }
        c = colorChrome(tone(c))
        if color != 0 {   // Fujifilm "Color": saturation (+4 → ×1.3, −4 → ×0.7)
            let y = 0.2126 * c.x + 0.7152 * c.y + 0.0722 * c.z
            c = y + (c - y) * (1 + 0.075 * Double(color))
        }
        return simd_clamp(c, .zero, .one)
    }

    /// Core Image colour-cube data (RGBA float32, red fastest) for the look, its strength and these settings.
    func cubeData(look: LUT?, intensity: Float, size: Int = 33) -> Data {
        var rgba = [Float](repeating: 1, count: size * size * size * 4)
        let gains = wbGains, t = Double(max(0, min(1, intensity))), s = 1 / Double(size - 1)
        var i = 0
        for b in 0..<size {
            for g in 0..<size {
                for r in 0..<size {
                    let o = apply(SIMD3(Double(r) * s, Double(g) * s, Double(b) * s), gains: gains, look: look, intensity: t)
                    rgba[i] = Float(o.x); rgba[i + 1] = Float(o.y); rgba[i + 2] = Float(o.z)
                    i += 4
                }
            }
        }
        return rgba.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    private static func toLin(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
    private static func toSrgb(_ v: Double) -> Double { v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1 / 2.4) - 0.055 }
}

private func clamp01(_ v: Double) -> Double { min(1, max(0, v)) }
private func smoothstep(_ a: Double, _ b: Double, _ x: Double) -> Double {
    let t = clamp01((x - a) / (b - a)); return t * t * (3 - 2 * t)
}
