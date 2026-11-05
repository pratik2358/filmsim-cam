import CoreImage
import os

/// Portrait (depth-of-field) settings shared between the UI and the capture/preview threads.
struct PortraitSettings: Sendable {
    var enabled = false
    var aperture: Float = 2.8              // simulated f-number; smaller = more blur
    var focusPoint = CGPoint(x: 0.5, y: 0.5)   // normalized, portrait, origin top-left
    var userFocused = false                // true once the user tapped a subject
    var focusDisparity: Float?             // smoothed disparity at the focus point
    static let apertureRange: ClosedRange<Float> = 1.4...16
}

enum Portrait {
    static let settings = OSAllocatedUnfairLock(initialState: PortraitSettings())

    /// Live-preview depth blur. `disparity` is the camera's depth map (higher = nearer), already
    /// oriented like `image`. Blur grows with distance in depth from the focus point.
    static func previewBlur(_ image: CIImage, disparity: CIImage, focus: Float, aperture: Float) -> CIImage {
        let ext = image.extent
        let d = disparity.extent
        guard d.width > 0, focus > 0.0001 else { return image }
        // Mask = (k·(disparity − focus)/focus)², clamped to 0…1, so the subject stays sharp.
        let k: Float = 2.4
        let a0 = CGFloat(k * k), a1 = CGFloat(-2 * k * k / focus), a2 = CGFloat(k * k / (focus * focus))
        let poly = CIVector(x: a0, y: a1, z: a2, w: 0)
        let mask = disparity
            .applyingFilter("CIColorMatrix", parameters: [          // copy depth into R, G and B
                "inputRVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                "inputBVector": CIVector(x: 1, y: 0, z: 0, w: 0),
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            ])
            .applyingFilter("CIColorPolynomial", parameters: [
                "inputRedCoefficients": poly, "inputGreenCoefficients": poly, "inputBlueCoefficients": poly,
            ])
            .applyingFilter("CIColorClamp", parameters: [
                "inputMinComponents": CIVector(x: 0, y: 0, z: 0, w: 1),
                "inputMaxComponents": CIVector(x: 1, y: 1, z: 1, w: 1),
            ])
            .transformed(by: CGAffineTransform(translationX: -d.minX, y: -d.minY))
            .transformed(by: CGAffineTransform(scaleX: ext.width / d.width, y: ext.height / d.height))
            .transformed(by: CGAffineTransform(translationX: ext.minX, y: ext.minY))
            .clampedToExtent()
            .applyingGaussianBlur(sigma: Double(min(ext.width, ext.height) / 220))   // soften depth edges
            .cropped(to: ext)
        let radius = 26 * (2.0 / max(aperture, 1)) * Float(min(ext.width, ext.height) / 1080)
        return image.clampedToExtent()
            .applyingFilter("CIMaskedVariableBlur", parameters: ["inputMask": mask, kCIInputRadiusKey: radius])
            .cropped(to: ext)
    }

    /// Average disparity in a small window around a normalized (top-left origin) point.
    static func sampleDisparity(_ disparity: CIImage, at p: CGPoint, context: CIContext) -> Float? {
        let e = disparity.extent
        let size = max(4, min(e.width, e.height) * 0.06)
        let rect = CGRect(x: e.minX + p.x * e.width - size / 2, y: e.minY + (1 - p.y) * e.height - size / 2,
                          width: size, height: size).intersection(e)
        guard !rect.isEmpty else { return nil }
        let avg = disparity.applyingFilter("CIAreaAverage", parameters: [kCIInputExtentKey: CIVector(cgRect: rect)])
        var px = [Float](repeating: 0, count: 4)
        context.render(avg, toBitmap: &px, rowBytes: 16, bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
                       format: .RGBAf, colorSpace: nil)
        let v = px[0]
        return v.isFinite && v > 0 ? v : nil
    }
}
