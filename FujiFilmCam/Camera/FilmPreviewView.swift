import SwiftUI
import MetalKit
import CoreImage

/// Draws the latest camera frame with the current film look applied, aspect-filled (centred crop).
struct FilmPreviewView: UIViewRepresentable {
    let frames: FrameStore
    /// Fit (letterbox) instead of fill: used for library photos so they're shown whole.
    var fit = false

    func makeUIView(context: Context) -> PreviewMTKView {
        PreviewMTKView(frames: frames)
    }
    func updateUIView(_ view: PreviewMTKView, context: Context) { view.fit = fit }
}

final class PreviewMTKView: MTKView, MTKViewDelegate {
    private let frames: FrameStore
    private let renderer = FilmRenderer.shared
    private lazy var queue = device?.makeCommandQueue()
    private var drawn = (frame: -1, settings: -1, size: CGSize.zero)
    private let outputSpace = CGColorSpace(name: CGColorSpace.displayP3)!
    var fit = false { didSet { if fit != oldValue { drawn.frame = -1 } } }

    init(frames: FrameStore) {
        self.frames = frames
        super.init(frame: .zero, device: FilmRenderer.shared.device)
        framebufferOnly = false
        colorPixelFormat = .bgra8Unorm
        preferredFramesPerSecond = 60
        autoResizeDrawable = true
        isOpaque = true
        backgroundColor = .black
        clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        // Let touches through to the SwiftUI gestures (tap to focus, drag for exposure, swipe looks).
        isUserInteractionEnabled = false
        delegate = self
    }
    required init(coder: NSCoder) { fatalError() }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        let (image, frameVersion) = frames.current
        let settingsVersion = renderer.version
        let size = drawableSize
        // Redraw only when the frame, the look or the size changed (keeps stills cheap).
        guard frameVersion != drawn.frame || settingsVersion != drawn.settings || size != drawn.size else { return }
        guard let image, size.width > 0, let queue, let drawable = currentDrawable,
              let buffer = queue.makeCommandBuffer() else { return }
        drawn = (frameVersion, settingsVersion, size)

        let src = image.extent
        let sx = size.width / src.width, sy = size.height / src.height
        let scale = fit ? min(sx, sy) : max(sx, sy)
        // Scale to screen size first so the look (and portrait blur) only run on visible pixels.
        var scaled = image
            .transformed(by: CGAffineTransform(translationX: -src.minX, y: -src.minY))
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let portrait = Portrait.settings.withLock { $0 }
        if portrait.enabled, let d = frames.disparity, let focus = portrait.focusDisparity {
            scaled = Portrait.previewBlur(scaled, disparity: d, focus: focus, aperture: portrait.aperture)
        }
        scaled = renderer.apply(to: scaled, underexposedEV: fit ? 0 : renderer.drGainEV)   // fit = a library photo
        let dx = (size.width - src.width * scale) / 2, dy = (size.height - src.height * scale) / 2
        let placed = scaled.transformed(by: CGAffineTransform(translationX: dx, y: dy))
            .composited(over: CIImage(color: .black))
            .cropped(to: CGRect(origin: .zero, size: size))

        let dest = CIRenderDestination(width: Int(size.width), height: Int(size.height),
                                       pixelFormat: colorPixelFormat, commandBuffer: buffer) { drawable.texture }
        dest.colorSpace = outputSpace
        _ = try? renderer.context.startTask(toRender: placed, to: dest)
        buffer.present(drawable)
        buffer.commit()
    }
}
