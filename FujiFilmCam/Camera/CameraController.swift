@preconcurrency import AVFoundation
import CoreImage
import Photos
import UIKit
import os

/// Latest unfiltered frame (or a still picked from the library) shared with the preview.
final class FrameStore: @unchecked Sendable {
    private struct State { var image: CIImage?; var disparity: CIImage?; var version = 0 }
    private let lock = OSAllocatedUnfairLock(initialState: State())
    func set(_ image: CIImage?) { lock.withLock { $0.image = image; $0.version += 1 } }
    func setDisparity(_ d: CIImage?) { lock.withLock { $0.disparity = d } }
    var current: (image: CIImage?, version: Int) { lock.withLock { ($0.image, $0.version) } }
    var disparity: CIImage? { lock.withLock { $0.disparity } }
}

@MainActor
final class CameraController: NSObject, ObservableObject {
    enum Status: Equatable { case idle, running, denied, unavailable, failed(String) }
    enum Flash: CaseIterable { case off, auto, on
        var mode: AVCaptureDevice.FlashMode { switch self { case .off: .off; case .auto: .auto; case .on: .on } }
        var symbol: String { switch self { case .off: "bolt.slash.fill"; case .auto: "bolt.badge.automatic.fill"; case .on: "bolt.fill" } }
    }

    @Published private(set) var status: Status = .idle
    @Published private(set) var position: AVCaptureDevice.Position = .back
    @Published private(set) var zoomStops: [CGFloat] = [1]     // displayed values, e.g. 0.5, 1, 2
    @Published private(set) var zoom: CGFloat = 1
    @Published var flash: Flash = .off
    @Published private(set) var isCapturing = false
    @Published private(set) var lastPhoto: UIImage?
    @Published var message: String?
    /// When true the preview shows a still image (from the library) instead of the live feed.
    @Published private(set) var isShowingStill = false
    /// Portrait (depth) mode: needs a camera that can measure depth (dual back cameras or TrueDepth).
    @Published private(set) var portraitSupported = false
    @Published private(set) var isPortrait = false

    let frames = FrameStore()

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "camera.session")
    private let videoQueue = DispatchQueue(label: "camera.video", qos: .userInteractive)
    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private let depthOutput = AVCaptureDepthDataOutput()
    private let depthQueue = DispatchQueue(label: "camera.depth", qos: .userInteractive)
    private var input: AVCaptureDeviceInput?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var zoomBase: CGFloat = 1                // device zoom factor that displays as "1×"
    private var inFlight: [Int64: PhotoDelegate] = [:]
    private var stillSource: CIImage?

    // MARK: Lifecycle

    func start() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: break
        case .notDetermined:
            guard await AVCaptureDevice.requestAccess(for: .video) else { status = .denied; return }
        default: status = .denied; return
        }
        if input == nil {
            guard configure(position: position) else {
                if status == .idle { status = .unavailable }
                return
            }
        }
        let session = session
        await withCheckedContinuation { cont in
            sessionQueue.async { if !session.isRunning { session.startRunning() }; cont.resume() }
        }
        status = .running
    }

    func stop() {
        let session = session
        sessionQueue.async { if session.isRunning { session.stopRunning() } }
    }

    private func configure(position: AVCaptureDevice.Position) -> Bool {
        guard let device = Self.bestDevice(for: position) else { return false }
        do {
            let newInput = try AVCaptureDeviceInput(device: device)
            session.beginConfiguration()
            defer { session.commitConfiguration() }
            session.sessionPreset = .photo
            if let input { session.removeInput(input) }
            guard session.canAddInput(newInput) else { status = .failed("Can't use this camera."); return false }
            session.addInput(newInput)
            input = newInput

            if !session.outputs.contains(videoOutput), session.canAddOutput(videoOutput) {
                videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                videoOutput.alwaysDiscardsLateVideoFrames = true
                videoOutput.setSampleBufferDelegate(self, queue: videoQueue)
                session.addOutput(videoOutput)
            }
            if !session.outputs.contains(photoOutput), session.canAddOutput(photoOutput) {
                session.addOutput(photoOutput)
                photoOutput.maxPhotoQualityPrioritization = .quality
            }
            if let max = device.activeFormat.supportedMaxPhotoDimensions.max(by: { $0.width * $0.height < $1.width * $1.height }) {
                photoOutput.maxPhotoDimensions = max
            }
            if let conn = videoOutput.connection(with: .video) {
                if conn.isVideoRotationAngleSupported(90) { conn.videoRotationAngle = 90 }
                conn.automaticallyAdjustsVideoMirroring = false
                conn.isVideoMirrored = position == .front
            }
            rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
            setupZoom(for: device)
            resetToContinuous(device)
            self.position = position
            portraitSupported = !device.activeFormat.supportedDepthDataFormats.isEmpty
            if isPortrait { isPortrait = false; DispatchQueue.main.async { [weak self] in self?.setPortrait(true) } }
            return true
        } catch {
            status = .failed(error.localizedDescription)
            return false
        }
    }

    private static func bestDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        let types: [AVCaptureDevice.DeviceType] = position == .back
            ? [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
            : [.builtInTrueDepthCamera, .builtInWideAngleCamera]
        return AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: position).devices.first
    }

    func flip() {
        guard status == .running else { return }
        let next: AVCaptureDevice.Position = position == .back ? .front : .back
        _ = configure(position: next)
    }

    // MARK: Zoom & focus

    private func setupZoom(for device: AVCaptureDevice) {
        let switches = device.virtualDeviceSwitchOverVideoZoomFactors.map { CGFloat(truncating: $0) }
        let hasUltraWide = device.constituentDevices.contains { $0.deviceType == .builtInUltraWideCamera }
        zoomBase = hasUltraWide ? (switches.first ?? 1) : 1
        var stops: [CGFloat] = hasUltraWide ? [0.5, 1] : [1]
        let tele = switches.dropFirst(hasUltraWide ? 1 : 0).first.map { $0 / zoomBase }
        stops.append(tele ?? 2)
        zoomStops = stops
        setZoom(1)
    }

    func setZoom(_ displayed: CGFloat) {
        guard let device = input?.device else { return }
        let factor = min(max(displayed * zoomBase, device.minAvailableVideoZoomFactor),
                         min(device.maxAvailableVideoZoomFactor, zoomBase * 10))
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = factor
            device.unlockForConfiguration()
            zoom = factor / zoomBase
        } catch {}
    }

    // MARK: Viewfinder framing

    /// The part of the camera frame the viewfinder shows (normalized, portrait, top-left origin).
    /// The viewfinder is aspect-filled, so a taller screen crops the sides; photos are cropped to match.
    private(set) var visibleRect = CGRect(x: 0, y: 0, width: 1, height: 1)

    /// Called by the UI with the viewfinder's on-screen size.
    func setViewfinderSize(_ size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        let src: CGFloat = {
            guard let e = frames.current.image?.extent, e.width > 0, e.height > 0, !isShowingStill else { return 3 / 4 }
            return e.width / e.height
        }()
        let view = size.width / size.height
        let rect: CGRect
        if abs(view - src) < 0.002 { rect = CGRect(x: 0, y: 0, width: 1, height: 1) }
        else if view < src { let w = view / src; rect = CGRect(x: (1 - w) / 2, y: 0, width: w, height: 1) }
        else { let h = src / view; rect = CGRect(x: 0, y: (1 - h) / 2, width: 1, height: h) }
        if rect != visibleRect {
            visibleRect = rect
            if let device = input?.device, focusPoint != nil { resetToContinuous(device) }   // old tap is in the old framing
        }
    }

    /// Viewfinder point -> camera-frame point.
    private func framePoint(_ p: CGPoint) -> CGPoint {
        CGPoint(x: visibleRect.minX + p.x * visibleRect.width, y: visibleRect.minY + p.y * visibleRect.height)
    }

    // MARK: Focus & exposure

    /// Where the user last tapped (normalized preview coords), nil when back on full auto.
    @Published private(set) var focusPoint: CGPoint?
    /// AE/AF lock (press and hold): stays put until the next tap, even if the scene changes.
    @Published private(set) var isFocusLocked = false
    /// Exposure compensation in EV.
    @Published private(set) var exposureBias: Float = 0
    static let exposureRange: ClosedRange<Float> = -2...2
    private var subjectObserver: NSObjectProtocol?

    /// Full auto: continuous focus & exposure on the centre, no compensation.
    private func resetToContinuous(_ device: AVCaptureDevice) {
        do {
            try device.lockForConfiguration()
            let centre = CGPoint(x: 0.5, y: 0.5)
            if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = centre }
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            if device.isExposurePointOfInterestSupported { device.exposurePointOfInterest = centre }
            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
            applyBias(0, device)
            device.isSubjectAreaChangeMonitoringEnabled = false
            device.unlockForConfiguration()
        } catch {}
        focusPoint = nil
        isFocusLocked = false
        exposureBias = 0
        Portrait.settings.withLock { $0.focusPoint = CGPoint(x: 0.5, y: 0.5); $0.userFocused = false; $0.focusDisparity = nil }
    }

    /// Tap: focus & expose on `point` (normalized preview coords, portrait, origin top-left).
    /// With `lock`, the result is held until the next tap (AE/AF lock); otherwise the camera
    /// returns to full auto when the scene changes, like the system Camera app.
    func focus(at point: CGPoint, lock: Bool = false) {
        guard let device = input?.device else { return }
        let fp = framePoint(point)
        // Portrait frame point -> the sensor's landscape coordinate space.
        var p = CGPoint(x: fp.y, y: 1 - fp.x)
        if position == .front { p.y = 1 - p.y }
        do {
            try device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.autoFocus) {
                device.focusPointOfInterest = p
                device.focusMode = .autoFocus          // focuses once on the point, then holds
            }
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = p
                if lock, device.isExposureModeSupported(.autoExpose) { device.exposureMode = .autoExpose }
                else if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
            }
            applyBias(0, device)
            device.isSubjectAreaChangeMonitoringEnabled = !lock
            device.unlockForConfiguration()
        } catch { return }
        focusPoint = point
        isFocusLocked = lock
        exposureBias = 0
        Portrait.settings.withLock { $0.focusPoint = fp; $0.userFocused = true; $0.focusDisparity = nil }
        observeSubjectArea(device)
    }

    func setExposureBias(_ ev: Float) {
        guard let device = input?.device else { return }
        let v = min(max(ev, Self.exposureRange.lowerBound), Self.exposureRange.upperBound)
        do {
            try device.lockForConfiguration()
            applyBias(v, device)
            device.unlockForConfiguration()
            exposureBias = v
        } catch {}
    }

    /// Dynamic range: DR200/400 underexpose the sensor by 1/2 stops; the renderer brings the picture back up.
    private var drStops: Float = 0
    func updateDynamicRange(stops: Int) {
        guard Float(stops) != drStops else { return }
        drStops = Float(stops)
        setExposureBias(exposureBias)
    }

    /// Sets the device bias for the user's `ev` minus the DR underexposure (device must be locked for configuration).
    private func applyBias(_ ev: Float, _ device: AVCaptureDevice) {
        let hw = min(max(ev - drStops, device.minExposureTargetBias), device.maxExposureTargetBias)
        device.setExposureTargetBias(hw, completionHandler: nil)
        FilmRenderer.shared.drGainEV = drStops > 0 ? min(max(ev - hw, 0), drStops) : 0
    }

    private func observeSubjectArea(_ device: AVCaptureDevice) {
        if let subjectObserver { NotificationCenter.default.removeObserver(subjectObserver) }
        subjectObserver = NotificationCenter.default.addObserver(
            forName: AVCaptureDevice.subjectAreaDidChangeNotification, object: device, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, !self.isFocusLocked, let device = self.input?.device else { return }
                self.resetToContinuous(device)
            }
        }
    }

    // MARK: Portrait (depth)

    func setPortrait(_ on: Bool) {
        guard let device = input?.device else { return }
        if on && !portraitSupported { return }
        session.beginConfiguration()
        if on {
            if !session.outputs.contains(depthOutput), session.canAddOutput(depthOutput) {
                session.addOutput(depthOutput)
                depthOutput.isFilteringEnabled = true          // smooth, hole-filled depth
                depthOutput.alwaysDiscardsLateDepthData = true
                depthOutput.setDelegate(self, callbackQueue: depthQueue)
            }
            if let conn = depthOutput.connection(with: .depthData) {
                if conn.isVideoRotationAngleSupported(90) { conn.videoRotationAngle = 90 }
                if conn.isVideoMirroringSupported { conn.automaticallyAdjustsVideoMirroring = false; conn.isVideoMirrored = position == .front }
            }
            // Highest-resolution disparity format for the current video format.
            let formats = device.activeFormat.supportedDepthDataFormats
                .filter { CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_DisparityFloat16 }
            if let best = formats.max(by: { CMVideoFormatDescriptionGetDimensions($0.formatDescription).width < CMVideoFormatDescriptionGetDimensions($1.formatDescription).width }),
               (try? device.lockForConfiguration()) != nil {
                device.activeDepthDataFormat = best
                device.unlockForConfiguration()
            }
            photoOutput.isDepthDataDeliveryEnabled = photoOutput.isDepthDataDeliverySupported
            photoOutput.isPortraitEffectsMatteDeliveryEnabled = photoOutput.isPortraitEffectsMatteDeliverySupported
        } else {
            if session.outputs.contains(depthOutput) { session.removeOutput(depthOutput) }
            photoOutput.isPortraitEffectsMatteDeliveryEnabled = false
            photoOutput.isDepthDataDeliveryEnabled = false
        }
        session.commitConfiguration()
        let active = on && photoOutput.isDepthDataDeliveryEnabled
        isPortrait = active
        if active {
            setZoom(1)                                        // depth comes from the 1× camera pair
            // Stay inside a zoom range the camera can measure depth in.
            if #available(iOS 17.2, *) {
                let ranges = device.activeFormat.supportedVideoZoomRangesForDepthDataDelivery
                if !ranges.isEmpty, !ranges.contains(where: { $0.contains(device.videoZoomFactor) }),
                   let r = ranges.min(by: { abs($0.lowerBound - zoomBase) < abs($1.lowerBound - zoomBase) }),
                   (try? device.lockForConfiguration()) != nil {
                    device.videoZoomFactor = min(max(zoomBase, r.lowerBound), r.upperBound)
                    device.unlockForConfiguration()
                    zoom = device.videoZoomFactor / zoomBase
                }
            }
        }
        frames.setDisparity(nil)
        Portrait.settings.withLock { $0.enabled = active; $0.focusDisparity = nil }
        if on && !active { message = "Portrait isn't available with this camera." }
    }

    func setAperture(_ f: Float) {
        Portrait.settings.withLock { $0.aperture = min(max(f, PortraitSettings.apertureRange.lowerBound), PortraitSettings.apertureRange.upperBound) }
    }

    // MARK: Library stills

    func showStill(_ image: CIImage?) {
        stillSource = image
        isShowingStill = image != nil
        stillFlag.withLock { $0 = image != nil }
        if image != nil { frames.setDisparity(nil) }
        frames.set(image)
    }

    // MARK: Capture

    func capture() {
        if isShowingStill, let still = stillSource {
            isCapturing = true
            Task.detached(priority: .userInitiated) { [weak self] in
                let result = PhotoProcessor.render(still, properties: still.properties)
                await self?.finish(result)
            }
            return
        }
        guard status == .running, !isCapturing else { return }
        isCapturing = true
        let settings = photoOutput.availablePhotoCodecTypes.contains(.hevc)
            ? AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc]) : AVCapturePhotoSettings()
        if photoOutput.supportedFlashModes.contains(flash.mode) { settings.flashMode = flash.mode }
        settings.photoQualityPrioritization = .balanced
        settings.maxPhotoDimensions = photoOutput.maxPhotoDimensions
        let portrait = isPortrait && photoOutput.isDepthDataDeliveryEnabled
        if portrait {
            settings.isDepthDataDeliveryEnabled = true
            settings.isDepthDataFiltered = true
            settings.embedsDepthDataInPhoto = true
            if photoOutput.isPortraitEffectsMatteDeliveryEnabled {
                settings.isPortraitEffectsMatteDeliveryEnabled = true
                settings.embedsPortraitEffectsMatteInPhoto = true
            }
        }
        let portraitParams: PortraitSettings? = portrait ? Portrait.settings.withLock { $0 } : nil
        if let conn = photoOutput.connection(with: .video) {
            let angle = rotation?.videoRotationAngleForHorizonLevelCapture ?? 90
            if conn.isVideoRotationAngleSupported(angle) { conn.videoRotationAngle = angle }
        }
        let delegate = PhotoDelegate(portrait: portraitParams, crop: visibleRect.size, underexposedEV: FilmRenderer.shared.drGainEV) { [weak self] result in
            Task { @MainActor in
                self?.inFlight[settings.uniqueID] = nil
                self?.finish(result)
            }
        }
        inFlight[settings.uniqueID] = delegate
        photoOutput.capturePhoto(with: settings, delegate: delegate)
    }

    private func finish(_ result: Result<PhotoProcessor.Output, Error>) {
        isCapturing = false
        switch result {
        case .success(let output):
            lastPhoto = output.thumbnail
            Task {
                do { try await PhotoProcessor.saveToLibrary(output.data) }
                catch { message = error.localizedDescription }
            }
        case .failure(let error):
            message = error.localizedDescription
        }
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        var image = CIImage(cvPixelBuffer: pixelBuffer)
        if !connection.isVideoRotationAngleSupported(90) { image = image.oriented(.right) }
        if !stillFlag.withLock({ $0 }) { frames.set(image) }
    }
}

extension CameraController: AVCaptureDepthDataOutputDelegate {
    nonisolated func depthDataOutput(_ output: AVCaptureDepthDataOutput, didOutput depthData: AVDepthData,
                                     timestamp: CMTime, connection: AVCaptureConnection) {
        let disparityData = depthData.depthDataType == kCVPixelFormatType_DisparityFloat16
            ? depthData : depthData.converting(toDepthDataType: kCVPixelFormatType_DisparityFloat16)
        var d = CIImage(cvPixelBuffer: disparityData.depthDataMap)
        if !connection.isVideoRotationAngleSupported(90) {
            d = d.oriented(connection.isVideoMirrored ? .leftMirrored : .right)
        }
        d = d.transformed(by: CGAffineTransform(translationX: -d.extent.minX, y: -d.extent.minY))
        guard !stillFlag.withLock({ $0 }) else { frames.setDisparity(nil); return }
        frames.setDisparity(d)
        let point = Portrait.settings.withLock { $0.focusPoint }
        if let v = Portrait.sampleDisparity(d, at: point, context: FilmRenderer.shared.context) {
            Portrait.settings.withLock { s in
                s.focusDisparity = s.focusDisparity.map { $0 * 0.8 + v * 0.2 } ?? v   // smooth over frames
            }
        }
    }
}

/// Nonisolated mirror of `isShowingStill`, readable from the video queue.
private let stillFlag = OSAllocatedUnfairLock(initialState: false)

// MARK: - Photo delegate & processing

private final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    let completion: (Result<PhotoProcessor.Output, Error>) -> Void
    let portrait: PortraitSettings?
    let crop: CGSize
    let underexposedEV: Float
    init(portrait: PortraitSettings?, crop: CGSize, underexposedEV: Float, completion: @escaping (Result<PhotoProcessor.Output, Error>) -> Void) {
        self.portrait = portrait; self.crop = crop; self.underexposedEV = underexposedEV; self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error { completion(.failure(error)); return }
        guard let data = photo.fileDataRepresentation(),
              let image = CIImage(data: data, options: [.applyOrientationProperty: true]) else {
            completion(.failure(PhotoProcessor.Failure.encode)); return
        }
        let completion = completion, portrait = portrait, crop = crop, ev = underexposedEV
        DispatchQueue.global(qos: .userInitiated).async {
            var source = image
            if let portrait, let blurred = PhotoProcessor.portraitBlur(data: data, oriented: image, settings: portrait) {
                source = blurred
            }
            source = PhotoProcessor.centerCrop(source, portraitFraction: crop)   // save what the viewfinder showed
            completion(PhotoProcessor.render(source, properties: image.properties, underexposedEV: ev))
        }
    }
}

enum PhotoProcessor {
    struct Output: @unchecked Sendable { let data: Data; let thumbnail: UIImage? }
    enum Failure: LocalizedError {
        case encode, permission
        var errorDescription: String? {
            switch self {
            case .encode: "Couldn't process the photo."
            case .permission: "Allow Photos access in Settings to save pictures."
            }
        }
    }

    /// Keeps the centred `portraitFraction` (width, height) of the image; swapped for a sideways photo.
    static func centerCrop(_ image: CIImage, portraitFraction f: CGSize) -> CIImage {
        guard f.width < 0.999 || f.height < 0.999 else { return image }
        let e = image.extent
        let (fw, fh) = e.width > e.height ? (f.height, f.width) : (f.width, f.height)
        let w = (e.width * fw).rounded(), h = (e.height * fh).rounded()
        let rect = CGRect(x: (e.minX + (e.width - w) / 2).rounded(), y: (e.minY + (e.height - h) / 2).rounded(), width: w, height: h)
        return image.cropped(to: rect).transformed(by: CGAffineTransform(translationX: -rect.minX, y: -rect.minY))
    }

    static func render(_ image: CIImage, properties: [String: Any], underexposedEV: Float = 0) -> Result<Output, Error> {
        let renderer = FilmRenderer.shared
        let extent = image.extent
        let filtered = renderer.apply(to: image, animateGrain: false, underexposedEV: underexposedEV)

        var props = properties
        props[kCGImagePropertyOrientation as String] = 1
        if var tiff = props[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
            tiff[kCGImagePropertyTIFFOrientation as String] = 1
            props[kCGImagePropertyTIFFDictionary as String] = tiff
        }
        let space = image.colorSpace ?? CGColorSpace(name: CGColorSpace.displayP3)!
        guard let data = renderer.context.jpegRepresentation(
            of: filtered.settingProperties(props), colorSpace: space,
            options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.92]
        ) else { return .failure(Failure.encode) }

        let thumbScale = 240 / max(extent.width, extent.height)
        let thumb = renderer.context.createCGImage(
            filtered.transformed(by: CGAffineTransform(scaleX: thumbScale, y: thumbScale)),
            from: filtered.extent.applying(CGAffineTransform(scaleX: thumbScale, y: thumbScale)).integral
        ).map { UIImage(cgImage: $0) }
        return .success(Output(data: data, thumbnail: thumb))
    }

    /// Background blur for a photo captured with depth, using Apple's portrait renderer
    /// (it uses the depth map, the person cut-out matte and face positions when present).
    static func portraitBlur(data: Data, oriented: CIImage, settings: PortraitSettings) -> CIImage? {
        let ctx = FilmRenderer.shared.context
        guard let filter = ctx.depthBlurEffectFilter(forImageData: data, options: [CIImageOption.applyOrientationProperty: true]) else { return nil }
        filter.setValue(settings.aperture, forKey: "inputAperture")
        if settings.userFocused {
            // Normalized rect, Core Image coordinates (origin bottom-left).
            let s: CGFloat = 0.12
            let r = CGRect(x: settings.focusPoint.x - s / 2, y: (1 - settings.focusPoint.y) - s / 2, width: s, height: s)
            filter.setValue(CIVector(cgRect: r), forKey: "inputFocusRect")
        }
        guard var out = filter.outputImage else { return nil }
        // Some iOS versions return the un-rotated image; match the oriented photo.
        let want = oriented.extent.size
        if abs(out.extent.width - want.width) > 2 || abs(out.extent.height - want.height) > 2,
           let raw = oriented.properties[kCGImagePropertyOrientation as String] as? UInt32,
           let o = CGImagePropertyOrientation(rawValue: raw) {
            out = out.oriented(o)
        }
        out = out.transformed(by: CGAffineTransform(translationX: -out.extent.minX, y: -out.extent.minY))
        guard abs(out.extent.width - want.width) <= 2 else { return nil }
        return out.cropped(to: CGRect(origin: .zero, size: want))
    }

    static func saveToLibrary(_ data: Data) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw Failure.permission }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: nil)
        }
    }
}
