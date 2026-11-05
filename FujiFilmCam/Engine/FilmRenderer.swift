import CoreImage
import Metal
import os

/// Applies the current film look to a `CIImage`. Thread-safe: settings are written on the
/// main thread, while images may be rendered from the main thread (preview) or a capture callback.
final class FilmRenderer: @unchecked Sendable {
    static let shared = FilmRenderer()

    let device: MTLDevice?
    let context: CIContext

    private struct State {
        var cube: CIFilter?
        var grain: Float = 0
        var version = 0
    }
    private let state = OSAllocatedUnfairLock(initialState: State())
    private let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
    private let noise = CIFilter(name: "CIRandomGenerator")!.outputImage!

    private init() {
        device = MTLCreateSystemDefaultDevice()
        let opts: [CIContextOption: Any] = [.cacheIntermediates: false, .name: "FilmRenderer"]
        context = device.map { CIContext(mtlDevice: $0, options: opts) } ?? CIContext(options: opts)
    }

    /// Bumped whenever settings change, so a still-image preview knows to redraw.
    var version: Int { state.withLock { $0.version } }

    func configure(lut: LUT?, intensity: Float, grain: Float) {
        var cube: CIFilter?
        if let lut, intensity > 0.001 {
            let f = CIFilter(name: "CIColorCubeWithColorSpace")!
            f.setValue(lut.size, forKey: "inputCubeDimension")
            f.setValue(lut.colorCubeData(intensity: intensity), forKey: "inputCubeData")
            f.setValue(srgb, forKey: "inputColorSpace")
            cube = f
        }
        let newCube = cube
        state.withLock {
            $0.cube = newCube
            $0.grain = grain
            $0.version += 1
        }
    }

    /// Grain particle size is relative to the image (≈1px per 1080px of short edge),
    /// so the live preview and the full-resolution photo look the same.
    func apply(to image: CIImage, animateGrain: Bool = true) -> CIImage {
        let grainScale = max(1, min(image.extent.width, image.extent.height) / 1080)
        let (cube, grain) = state.withLock { s -> (CIFilter?, Float) in
            (s.cube?.copy() as? CIFilter, s.grain)
        }
        var out = image
        if let cube {
            cube.setValue(out, forKey: kCIInputImageKey)
            out = cube.outputImage ?? out
        }
        if grain > 0 { out = addGrain(to: out, amount: grain, scale: grainScale, animate: animateGrain) }
        return out
    }

    private func addGrain(to image: CIImage, amount: Float, scale: CGFloat, animate: Bool) -> CIImage {
        let extent = image.extent
        let offset = animate ? CGFloat.random(in: 0...512) : 0
        // Monochrome noise centred on 0, scaled so particle size follows the output resolution.
        let a = CGFloat(amount) * 0.16
        let mono = noise
            .transformed(by: CGAffineTransform(translationX: offset, y: offset))
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: a, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: a, y: 0, z: 0, w: 0),
                "inputBVector": CIVector(x: a, y: 0, z: 0, w: 0),
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
                "inputBiasVector": CIVector(x: -a / 2, y: -a / 2, z: -a / 2, w: 0),
            ])
            .transformed(by: CGAffineTransform(scaleX: max(1, scale), y: max(1, scale)))
            .cropped(to: extent)
        // Grain is strongest in midtones, like real film.
        let midtoneMask = image
            .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 0])
            .applyingFilter("CIColorPolynomial", parameters: [
                "inputRedCoefficients": CIVector(x: 0, y: 4, z: -4, w: 0),
                "inputGreenCoefficients": CIVector(x: 0, y: 4, z: -4, w: 0),
                "inputBlueCoefficients": CIVector(x: 0, y: 4, z: -4, w: 0),
            ])
        let weighted = mono.applyingFilter("CIMultiplyCompositing", parameters: [kCIInputBackgroundImageKey: midtoneMask])
        return weighted
            .applyingFilter("CIAdditionCompositing", parameters: [kCIInputBackgroundImageKey: image])
            .cropped(to: extent)
    }
}
