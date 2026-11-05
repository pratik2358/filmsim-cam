import Foundation
import simd

/// A colour "recipe" baked into a 3D LUT. Works in OKLab/OKLCh (a perceptual space), which lets
/// each look control tone, per-hue hue/chroma/lightness, and luminance-dependent colour the way
/// film simulations do. The camera-style looks are hand-tuned approximations based on the
/// published characteristics of each simulation. They are not Fujifilm data.
struct LookRecipe: Sendable {
    /// Hue anchors (OKLCh degrees). Values between anchors are interpolated smoothly.
    enum Hue: Float, CaseIterable, Sendable {
        case red = 25, skin = 58, yellow = 100, green = 140, cyan = 195, blue = 255, purple = 300, magenta = 345
    }
    struct HueAdjust: Sendable {
        var hue: Float = 0         // degrees; + rotates toward the next anchor (red→skin→yellow→green…)
        var chroma: Float = 1      // saturation multiplier
        var lightness: Float = 0   // OKLab L offset for saturated colours (e.g. −0.05 = noticeably darker)
    }

    // Tone (applied to OKLab L, mid-grey pivot)
    var contrast: Float = 1        // slope through mid-grey
    var shadows: Float = 0         // + harder/deeper shadows, − lifted shadows
    var highlights: Float = 0      // + brighter/harder highlights, − softer roll-off
    var blackLift: Float = 0       // raises the darkest tone (matte)
    var exposure: Float = 0        // stops, applied in linear light

    // Colour
    var saturation: Float = 1
    var shadowSaturation: Float = 1    // extra multiplier in the shadows
    var highlightSaturation: Float = 1 // extra multiplier in the highlights (film desaturates near white)
    var hues: [Hue: HueAdjust] = [:]
    var colorChrome: Float = 0         // darkens highly saturated colours, adding depth (Fuji "Color Chrome Effect")
    var colorChromeBlue: Float = 0     // same, blues only ("Color Chrome FX Blue")
    var warmth: Float = 0              // + amber, − blue (white-balance shift)
    var tint: Float = 0                // + magenta, − green
    var shadowTint = SIMD2<Float>(0, 0)    // OKLab (a, b) offset; a: +magenta/−green, b: +yellow/−blue
    var highlightTint = SIMD2<Float>(0, 0)

    var mono: SIMD3<Float>? = nil      // linear channel-mix weights for black & white
    var monoTone = SIMD2<Float>(0, 0)  // (a, b) toning for black & white

    func makeLUT(size: Int = LUT.standardSize) -> LUT {
        let table = HueTable(hues)
        return LUT.generate(size: size) { apply($0, table: table) }
    }

    func apply(_ input: SIMD3<Float>) -> SIMD3<Float> { apply(input, table: HueTable(hues)) }

    private func apply(_ input: SIMD3<Float>, table: HueTable) -> SIMD3<Float> {
        var lin = srgbToLinear(input)
        lin *= SIMD3(1 + warmth * 0.08, 1 - tint * 0.05, 1 - warmth * 0.08) * powf(2, exposure)
        lin = simd_max(lin, SIMD3(repeating: 0))

        if let w = mono {
            let y = simd_dot(lin, w / (w.x + w.y + w.z))
            let L = tone(cbrtf(max(0, y)))
            let fade = (1 - L) * L * 4  // toning strongest in midtones
            return labToSrgbClipped(SIMD3(L, monoTone.x * fade, monoTone.y * fade))
        }

        let lab = linearToOklab(lin)
        let L0 = lab.x
        var C = simd_length(SIMD2(lab.y, lab.z))
        var h = atan2f(lab.z, lab.y) * 180 / .pi
        if h < 0 { h += 360 }

        // Tone. Chroma follows the tone change partly, as on film.
        var L = tone(L0)
        if L0 > 1e-4 { C *= powf(max(L, 1e-4) / L0, 0.4) }

        // Per-hue adjustments, faded out for near-neutral colours so greys stay grey.
        let adj = table.value(at: h)
        let colourful = smoothstep(0.01, 0.06, C)
        h += adj.hue * colourful
        C *= 1 + (adj.chroma - 1) * colourful
        L += adj.lightness * min(1, C / 0.12)

        // Global and luminance-dependent saturation.
        let shadowW = 1 - smoothstep(0.15, 0.55, L)
        let highW = smoothstep(0.7, 1.0, L)
        C *= saturation * (1 + (shadowSaturation - 1) * shadowW) * (1 + (highlightSaturation - 1) * highW)

        // Colour Chrome: deepen strongly saturated colours.
        let blueW = gaussian(angleDistance(h, 255), 35)
        L -= (colorChrome + colorChromeBlue * blueW) * 0.35 * powf(min(C, 0.3), 1.5) * 4

        let hr = h * .pi / 180
        var a = C * cosf(hr), b = C * sinf(hr)

        // Split toning by luminance.
        let sw = (1 - L) * (1 - L) * smoothstep(0.02, 0.3, L)
        let hw = L * L * (1 - smoothstep(0.88, 1.0, L))
        a += shadowTint.x * sw + highlightTint.x * hw
        b += shadowTint.y * sw + highlightTint.y * hw

        return labToSrgbClipped(SIMD3(L, a, b))
    }

    /// Smooth two-sided tone curve on OKLab L with a mid-grey pivot.
    private func tone(_ x: Float) -> Float {
        let p: Float = 0.57                                     // OKLab L of ~18% grey
        let gS = max(0.3, contrast * (1 + shadows))             // shadow-side exponent
        let gH = max(0.3, contrast * (1 + highlights))          // highlight-side exponent
        let lo = p * powf(max(0, x) / p, gS)
        let hi = 1 - (1 - p) * powf(max(0, 1 - x) / (1 - p), gH)
        let w = smoothstep(p - 0.12, p + 0.12, x)
        let y = lo + (hi - lo) * w
        return blackLift + min(1, max(0, y)) * (1 - blackLift)
    }

    /// Converts OKLab to sRGB, reducing chroma (keeping lightness and hue) if out of gamut.
    private func labToSrgbClipped(_ lab: SIMD3<Float>) -> SIMD3<Float> {
        let L = min(1, max(0, lab.x))
        var lin = oklabToLinear(SIMD3(L, lab.y, lab.z))
        if lin.min() < 0 || lin.max() > 1 {
            var lo: Float = 0, hi: Float = 1
            for _ in 0..<10 {
                let mid = (lo + hi) / 2
                let t = oklabToLinear(SIMD3(L, lab.y * mid, lab.z * mid))
                if t.min() < 0 || t.max() > 1 { hi = mid } else { lo = mid }
            }
            lin = oklabToLinear(SIMD3(L, lab.y * lo, lab.z * lo))
        }
        return linearToSrgb(simd_clamp01(lin))
    }
}

/// Smoothly interpolated per-hue adjustments (normalised Gaussian kernel over hue anchors).
private struct HueTable {
    let anchors: [(angle: Float, adj: LookRecipe.HueAdjust)]
    init(_ hues: [LookRecipe.Hue: LookRecipe.HueAdjust]) {
        anchors = LookRecipe.Hue.allCases.map { ($0.rawValue, hues[$0] ?? .init()) }
    }
    func value(at h: Float) -> LookRecipe.HueAdjust {
        var sum = LookRecipe.HueAdjust(hue: 0, chroma: 0, lightness: 0), wsum: Float = 0
        for (angle, adj) in anchors {
            let w = gaussian(angleDistance(h, angle), 28)
            sum.hue += adj.hue * w; sum.chroma += adj.chroma * w; sum.lightness += adj.lightness * w
            wsum += w
        }
        return .init(hue: sum.hue / wsum, chroma: sum.chroma / wsum, lightness: sum.lightness / wsum)
    }
}

// MARK: - Colour maths

@inline(__always) private func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
    let t = min(1, max(0, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t)
}
@inline(__always) private func angleDistance(_ a: Float, _ b: Float) -> Float {
    let d = abs(a - b).truncatingRemainder(dividingBy: 360); return d > 180 ? 360 - d : d
}
@inline(__always) private func gaussian(_ d: Float, _ sigma: Float) -> Float { expf(-(d * d) / (2 * sigma * sigma)) }

private func srgbToLinear(_ c: SIMD3<Float>) -> SIMD3<Float> {
    func f(_ v: Float) -> Float { v <= 0.04045 ? v / 12.92 : powf((v + 0.055) / 1.055, 2.4) }
    return SIMD3(f(c.x), f(c.y), f(c.z))
}
private func linearToSrgb(_ c: SIMD3<Float>) -> SIMD3<Float> {
    func f(_ v: Float) -> Float { v <= 0.0031308 ? v * 12.92 : 1.055 * powf(v, 1 / 2.4) - 0.055 }
    return SIMD3(f(c.x), f(c.y), f(c.z))
}
private func linearToOklab(_ c: SIMD3<Float>) -> SIMD3<Float> {
    let l = cbrtf(0.4122214708 * c.x + 0.5363325363 * c.y + 0.0514459929 * c.z)
    let m = cbrtf(0.2119034982 * c.x + 0.6806995451 * c.y + 0.1073969566 * c.z)
    let s = cbrtf(0.0883024619 * c.x + 0.2817188376 * c.y + 0.6299787005 * c.z)
    return SIMD3(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
}
private func oklabToLinear(_ c: SIMD3<Float>) -> SIMD3<Float> {
    let l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z
    let m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z
    let s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z
    let l3 = l * l * l, m3 = m * m * m, s3 = s * s * s
    return SIMD3(4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
                 -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
                 -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3)
}

// MARK: - Looks
// Only Provia (Fuji's standard rendering, close to a normal phone photo) is built from a recipe.
// The other camera looks are LUTs fitted to Fujifilm's sample images (tools/fit-camera-looks.py).

extension LookRecipe {
    /// Standard: faithful, slightly vivid, moderate contrast.
    static let provia = LookRecipe(
        contrast: 1.06, shadows: 0.04, highlights: 0.0, saturation: 1.06,
        hues: [.blue: .init(hue: 2, chroma: 1.05), .red: .init(chroma: 1.04), .green: .init(chroma: 1.04)])
}
