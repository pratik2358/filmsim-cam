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
        var adjust = Adjustments.neutral
        var version = 0
    }
    private let state = OSAllocatedUnfairLock(initialState: State())
    /// Cubes are built off the main thread; only the newest request is kept (slider drags send many).
    private let bakeQueue = DispatchQueue(label: "FilmRenderer.bake", qos: .userInitiated)
    private let latestRequest = OSAllocatedUnfairLock(initialState: 0)
    private let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
    private let noise = CIFilter(name: "CIRandomGenerator")!.outputImage!

    private init() {
        device = MTLCreateSystemDefaultDevice()
        let opts: [CIContextOption: Any] = [.cacheIntermediates: false, .name: "FilmRenderer"]
        context = device.map { CIContext(mtlDevice: $0, options: opts) } ?? CIContext(options: opts)
    }

    /// Bumped whenever settings change, so a still-image preview knows to redraw.
    var version: Int { state.withLock { $0.version } }

    /// The look, its strength and the picture settings are baked into one colour cube,
    /// so the preview and the saved photo always match and extra settings cost nothing per frame.
    func configure(lut: LUT?, intensity: Float, grain: Float, adjust: Adjustments = .neutral) {
        let request = latestRequest.withLock { $0 += 1; return $0 }
        bakeQueue.async { [self] in
            guard latestRequest.withLock({ $0 }) == request else { return }   // superseded by a newer change
            var cube: CIFilter?
            let look = intensity > 0.001 ? lut : nil
            if look != nil || !adjust.isNeutralCube {
                let size = 33
                let f = CIFilter(name: "CIColorCubeWithColorSpace")!
                f.setValue(size, forKey: "inputCubeDimension")
                f.setValue(adjust.cubeData(look: look, intensity: intensity, size: size), forKey: "inputCubeData")
                f.setValue(srgb, forKey: "inputColorSpace")
                cube = f
            }
            let newCube = cube
            state.withLock {
                $0.cube = newCube
                $0.grain = grain
                $0.adjust = adjust
                $0.version += 1
            }
        }
    }

    /// Grain particle size is relative to the image (≈1px per 1080px of short edge),
    /// so the live preview and the full-resolution photo look the same.
    func apply(to image: CIImage, animateGrain: Bool = true) -> CIImage {
        let grainScale = max(1, min(image.extent.width, image.extent.height) / 1080)
        let (cube, grain, adjust) = state.withLock { s -> (CIFilter?, Float, Adjustments) in
            (s.cube?.copy() as? CIFilter, s.grain, s.adjust)
        }
        var out = detail(image, adjust)
        if let cube {
            cube.setValue(out, forKey: kCIInputImageKey)
            out = cube.outputImage ?? out
        }
        if grain > 0 { out = addGrain(to: out, amount: grain, scale: grainScale, animate: animateGrain) }
        return out
    }

    /// Sharpness (one-pixel unsharp mask, or a slight blur) and Clarity (wide, gentle local contrast), as in the web shader.
    private func detail(_ image: CIImage, _ a: Adjustments) -> CIImage {
        let extent = image.extent
        var out = image
        func blurred(_ r: Double) -> CIImage {
            out.clampedToExtent().applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: r]).cropped(to: extent)
        }
        func mix(_ target: CIImage, _ t: Double) -> CIImage {
            out.applyingFilter("CIDissolveTransition", parameters: [kCIInputTargetImageKey: target, kCIInputTimeKey: t]).cropped(to: extent)
        }
        if a.sharpness > 0 {
            out = out.clampedToExtent().applyingFilter("CIUnsharpMask", parameters: [kCIInputRadiusKey: 1.0, kCIInputIntensityKey: a.sharpAmount]).cropped(to: extent)
        } else if a.sharpness < 0 {
            out = mix(blurred(1.0), -a.sharpAmount)
        }
        if a.clarity != 0 {
            let r = 0.012 * Double(min(extent.width, extent.height))
            if a.clarity > 0 {
                out = out.clampedToExtent().applyingFilter("CIUnsharpMask", parameters: [kCIInputRadiusKey: r, kCIInputIntensityKey: a.clarityAmount * 0.8]).cropped(to: extent)
            } else {
                out = mix(blurred(r * 0.5), -a.clarityAmount * 0.8)
            }
        }
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
