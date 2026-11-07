import Foundation
import simd
import CoreGraphics
import ImageIO

/// A 3D colour lookup table in sRGB-encoded space.
/// `values` is RGB triples with red varying fastest, then green, then blue,
/// matching both Hald CLUT pixel order and Core Image's `CIColorCube` layout.
struct LUT: Sendable {
    let size: Int
    let values: [Float]   // count == size³ * 3

    static let standardSize = 33

    enum LoadError: LocalizedError {
        case unreadable, malformed(String)
        var errorDescription: String? {
            switch self {
            case .unreadable: return "The file could not be read."
            case .malformed(let why): return "Not a valid LUT: \(why)"
            }
        }
    }

    // MARK: Construction

    static func identity(size: Int = standardSize) -> LUT {
        generate(size: size) { $0 }
    }

    /// Build a LUT by evaluating `transform` on every lattice point (inputs in 0...1).
    static func generate(size: Int = standardSize, _ transform: (SIMD3<Float>) -> SIMD3<Float>) -> LUT {
        var v = [Float](repeating: 0, count: size * size * size * 3)
        let scale = 1 / Float(size - 1)
        var i = 0
        for b in 0..<size {
            for g in 0..<size {
                for r in 0..<size {
                    let out = simd_clamp01(transform(SIMD3(Float(r) * scale, Float(g) * scale, Float(b) * scale)))
                    v[i] = out.x; v[i + 1] = out.y; v[i + 2] = out.z
                    i += 3
                }
            }
        }
        return LUT(size: size, values: v)
    }
}

extension LUT {
    /// Compact bundled format: raw little-endian UInt16 RGB, 33³ entries.
    init(bundledData data: Data) throws {
        let count = data.count / 2
        let size = Int(round(cbrt(Double(count / 3))))
        guard size * size * size * 3 == count, size >= 2 else { throw LoadError.malformed("unexpected size") }
        var v = [Float](repeating: 0, count: count)
        data.withUnsafeBytes { raw in
            let p = raw.bindMemory(to: UInt16.self)
            for i in 0..<count { v[i] = Float(UInt16(littleEndian: p[i])) / 65535 }
        }
        self.init(size: size, values: v)
    }
}

extension LUT {
    // MARK: Sampling

    /// Trilinear lookup, input in 0...1.
    func sample(_ c: SIMD3<Float>) -> SIMD3<Float> {
        let n = Float(size - 1)
        let p = simd_clamp01(c) * n
        let i0 = SIMD3<Int>(Int(p.x), Int(p.y), Int(p.z))
        let i1 = SIMD3<Int>(min(i0.x + 1, size - 1), min(i0.y + 1, size - 1), min(i0.z + 1, size - 1))
        let t = p - SIMD3<Float>(Float(i0.x), Float(i0.y), Float(i0.z))
        func at(_ r: Int, _ g: Int, _ b: Int) -> SIMD3<Float> {
            let k = ((b * size + g) * size + r) * 3
            return SIMD3(values[k], values[k + 1], values[k + 2])
        }
        let c00 = mix(at(i0.x, i0.y, i0.z), at(i1.x, i0.y, i0.z), t.x)
        let c10 = mix(at(i0.x, i1.y, i0.z), at(i1.x, i1.y, i0.z), t.x)
        let c01 = mix(at(i0.x, i0.y, i1.z), at(i1.x, i0.y, i1.z), t.x)
        let c11 = mix(at(i0.x, i1.y, i1.z), at(i1.x, i1.y, i1.z), t.x)
        return mix(mix(c00, c10, t.y), mix(c01, c11, t.y), t.z)
    }

    func resampled(to newSize: Int) -> LUT {
        if newSize == size { return self }
        return LUT.generate(size: newSize) { sample($0) }
    }
}

// MARK: - Importers (.cube text and Hald CLUT images)

extension LUT {
    static func load(contentsOf url: URL) throws -> LUT {
        switch url.pathExtension.lowercased() {
        case "cube": return try parseCube(try String(contentsOf: url, encoding: .utf8))
        case "png", "tif", "tiff", "jpg", "jpeg": return try parseHald(url)
        default: throw LoadError.malformed("use a .cube file or a Hald CLUT .png")
        }
    }

    /// Adobe/Resolve `.cube` format.
    static func parseCube(_ text: String) throws -> LUT {
        var size = 0
        var lo = SIMD3<Float>(0, 0, 0), hi = SIMD3<Float>(1, 1, 1)
        var v: [Float] = []
        for raw in text.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            let parts = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
            let key = parts[0].uppercased()
            if key == "LUT_3D_SIZE", parts.count > 1, let n = Int(parts[1]) {
                size = n; v.reserveCapacity(n * n * n * 3)
            } else if key == "LUT_1D_SIZE" {
                throw LoadError.malformed("1D LUTs are not supported")
            } else if key == "DOMAIN_MIN", parts.count >= 4 {
                lo = SIMD3(Float(parts[1]) ?? 0, Float(parts[2]) ?? 0, Float(parts[3]) ?? 0)
            } else if key == "DOMAIN_MAX", parts.count >= 4 {
                hi = SIMD3(Float(parts[1]) ?? 1, Float(parts[2]) ?? 1, Float(parts[3]) ?? 1)
            } else if parts.count >= 3, let r = Float(parts[0]), let g = Float(parts[1]), let b = Float(parts[2]) {
                let c = (SIMD3(r, g, b) - lo) / (hi - lo)
                v.append(contentsOf: [c.x, c.y, c.z])
            }
        }
        guard size >= 2 else { throw LoadError.malformed("missing LUT_3D_SIZE") }
        guard v.count == size * size * size * 3 else {
            throw LoadError.malformed("expected \(size * size * size) entries, found \(v.count / 3)")
        }
        return LUT(size: size, values: v).resampled(to: standardSize)
    }

    /// Hald CLUT image (level L: L³×L³ pixels, cube size L²).
    static func parseHald(_ url: URL) throws -> LUT {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw LoadError.unreadable }
        let w = img.width
        let level = Int(round(cbrt(Double(w))))
        guard img.height == w, level * level * level == w else {
            throw LoadError.malformed("image must be a square Hald CLUT (e.g. 512×512 or 1728×1728)")
        }
        let cube = level * level
        var px = [UInt8](repeating: 0, count: w * w * 4)
        let ok = px.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: w, bitsPerComponent: 8,
                                      bytesPerRow: w * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: w))
            return true
        }
        guard ok else { throw LoadError.unreadable }
        var v = [Float](repeating: 0, count: cube * cube * cube * 3)
        for i in 0..<(cube * cube * cube) {
            v[i * 3] = Float(px[i * 4]) / 255
            v[i * 3 + 1] = Float(px[i * 4 + 1]) / 255
            v[i * 3 + 2] = Float(px[i * 4 + 2]) / 255
        }
        return LUT(size: cube, values: v).resampled(to: standardSize)
    }
}

@inline(__always) func simd_clamp01(_ v: SIMD3<Float>) -> SIMD3<Float> {
    simd_clamp(v, SIMD3(repeating: 0), SIMD3(repeating: 1))
}
@inline(__always) func mix(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ t: Float) -> SIMD3<Float> {
    a + (b - a) * t
}
