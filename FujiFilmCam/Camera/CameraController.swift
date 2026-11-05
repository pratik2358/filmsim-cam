@preconcurrency import AVFoundation
import CoreImage
import Photos
import UIKit
import os

/// Latest unfiltered frame (or a still picked from the library) shared with the preview.
final class FrameStore: @unchecked Sendable {
    private let lock = OSAllocatedUnfairLock<(CIImage?, Int)>(initialState: (nil, 0))
    func set(_ image: CIImage?) { lock.withLock { $0 = (image, $0.1 + 1) } }
    var current: (image: CIImage?, version: Int) { lock.withLock { ($0.0, $0.1) } }
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

    let frames = FrameStore()

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "camera.session")
    private let videoQueue = DispatchQueue(label: "camera.video", qos: .userInteractive)
    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
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
            device.setExposureTargetBias(0, completionHandler: nil)
            device.isSubjectAreaChangeMonitoringEnabled = false
            device.unlockForConfiguration()
        } catch {}
        focusPoint = nil
        isFocusLocked = false
        exposureBias = 0
    }

    /// Tap: focus & expose on `point` (normalized preview coords, portrait, origin top-left).
    /// With `lock`, the result is held until the next tap (AE/AF lock); otherwise the camera
    /// returns to full auto when the scene changes, like the system Camera app.
    func focus(at point: CGPoint, lock: Bool = false) {
        guard let device = input?.device else { return }
        // Portrait UI point -> the sensor's landscape coordinate space.
        var p = CGPoint(x: point.y, y: 1 - point.x)
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
            device.setExposureTargetBias(0, completionHandler: nil)
            device.isSubjectAreaChangeMonitoringEnabled = !lock
            device.unlockForConfiguration()
        } catch { return }
        focusPoint = point
        isFocusLocked = lock
        exposureBias = 0
        observeSubjectArea(device)
    }

    func setExposureBias(_ ev: Float) {
        guard let device = input?.device else { return }
        let lo = max(device.minExposureTargetBias, Self.exposureRange.lowerBound)
        let hi = min(device.maxExposureTargetBias, Self.exposureRange.upperBound)
        let v = min(max(ev, lo), hi)
        do {
            try device.lockForConfiguration()
            device.setExposureTargetBias(v, completionHandler: nil)
            device.unlockForConfiguration()
            exposureBias = v
        } catch {}
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

    // MARK: Library stills

    func showStill(_ image: CIImage?) {
        stillSource = image
        isShowingStill = image != nil
        stillFlag.withLock { $0 = image != nil }
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
        if let conn = photoOutput.connection(with: .video) {
            let angle = rotation?.videoRotationAngleForHorizonLevelCapture ?? 90
            if conn.isVideoRotationAngleSupported(angle) { conn.videoRotationAngle = angle }
        }
        let delegate = PhotoDelegate { [weak self] result in
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

/// Nonisolated mirror of `isShowingStill`, readable from the video queue.
private let stillFlag = OSAllocatedUnfairLock(initialState: false)

// MARK: - Photo delegate & processing

private final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    let completion: (Result<PhotoProcessor.Output, Error>) -> Void
    init(completion: @escaping (Result<PhotoProcessor.Output, Error>) -> Void) { self.completion = completion }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error { completion(.failure(error)); return }
        guard let data = photo.fileDataRepresentation(),
              let image = CIImage(data: data, options: [.applyOrientationProperty: true]) else {
            completion(.failure(PhotoProcessor.Failure.encode)); return
        }
        let completion = completion
        DispatchQueue.global(qos: .userInitiated).async {
            completion(PhotoProcessor.render(image, properties: image.properties))
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

    static func render(_ image: CIImage, properties: [String: Any]) -> Result<Output, Error> {
        let renderer = FilmRenderer.shared
        let extent = image.extent
        let filtered = renderer.apply(to: image, animateGrain: false)

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

    static func saveToLibrary(_ data: Data) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw Failure.permission }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: nil)
        }
    }
}
