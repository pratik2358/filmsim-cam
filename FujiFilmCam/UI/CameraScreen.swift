import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct CameraScreen: View {
    @StateObject private var camera = CameraController()
    @State private var library = FilmLibrary()

    @AppStorage("filmID") private var filmID = "classic-chrome"
    @AppStorage("intensity") private var intensity = 1.0
    @AppStorage("grain") private var grain = 0          // 0 off, 1 weak, 2 strong
    @AppStorage("grid") private var showGrid = false
    @AppStorage("portrait") private var wantPortrait = false
    @AppStorage("aperture") private var aperture = 2.8
    @AppStorage("frameRatio") private var frameRatio = FrameRatio.full
    // Picture settings (see Adjustments): tone, white balance, Color Chrome.
    @AppStorage("adj.highlight") private var adjHighlight = 0.0
    @AppStorage("adj.shadow") private var adjShadow = 0.0
    @AppStorage("adj.kelvin") private var adjKelvin = 5500.0
    @AppStorage("adj.wbRed") private var adjWBRed = 0.0
    @AppStorage("adj.wbBlue") private var adjWBBlue = 0.0
    @AppStorage("adj.chrome") private var adjChrome = 0
    @AppStorage("adj.chromeBlue") private var adjChromeBlue = 0
    @AppStorage("adj.color") private var adjColor = 0.0
    @AppStorage("adj.dr") private var adjDR = 100
    @AppStorage("adj.sharpness") private var adjSharpness = 0.0
    @AppStorage("adj.clarity") private var adjClarity = 0.0
    private var adjustments: Adjustments {
        Adjustments(highlight: adjHighlight, shadow: adjShadow, kelvin: adjKelvin, wbRed: adjWBRed, wbBlue: adjWBBlue,
                    chrome: adjChrome, chromeBlue: adjChromeBlue, color: Int(adjColor), dr: adjDR,
                    sharpness: Int(adjSharpness), clarity: Int(adjClarity))
    }

    @State private var touch: Touch?
    @State private var holdTask: Task<Void, Never>?
    @State private var showFocusUI = false
    @State private var focusUIToken = 0
    @State private var showBadge = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var importing = false
    @State private var showAbout = false
    @State private var flashScreen = false
    @State private var panel: Panel?
    @Environment(\.scenePhase) private var scenePhase

    private var film: Film { library.film(id: filmID) ?? FilmLibrary.original }

    /// Viewfinder (and photo) shape. Full fills the whole screen; the others fill the width.
    enum FrameRatio: String, CaseIterable {
        case standard = "3:4", tall = "9:16", full = "Full"
        var aspect: CGFloat? { switch self { case .standard: 3 / 4; case .tall: 9 / 16; case .full: nil } }
        var next: FrameRatio { let all = Self.allCases; return all[(all.firstIndex(of: self)! + 1) % all.count] }
    }

    var body: some View {
        // The viewfinder takes as much of the screen as it can; the bars float over it.
        GeometryReader { geo in
            ZStack(alignment: .top) {
                viewfinder(in: geo.size)
                VStack(spacing: 0) {
                    topBar
                        .background(LinearGradient(colors: [.black.opacity(0.4), .clear], startPoint: .top, endPoint: .bottom)
                            .ignoresSafeArea(edges: .top))
                    hud
                    Spacer(minLength: 0)
                    overlayRow
                    controls
                        .background(LinearGradient(stops: [.init(color: .clear, location: 0),
                                                           .init(color: .black.opacity(panel == nil ? 0.25 : 0.6), location: 0.25),
                                                           .init(color: .black.opacity(panel == nil ? 0.35 : 0.72), location: 1)],
                                                   startPoint: .top, endPoint: .bottom)
                            .ignoresSafeArea(edges: .bottom))
                }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task {
            applyLook()
            #if DEBUG
            // Debug aid: `-demoImage /path/to/photo.jpg` previews a still (handy in the Simulator).
            if let path = UserDefaults.standard.string(forKey: "demoImage"),
               let image = CIImage(contentsOf: URL(fileURLWithPath: path), options: [.applyOrientationProperty: true]) {
                camera.showStill(image)
            }
            if UserDefaults.standard.string(forKey: "openPanel") == "adjust" { panel = .adjust }   // `-openPanel adjust`
            #endif
            camera.setAperture(Float(aperture))
            await camera.start()
            if wantPortrait { camera.setPortrait(true) }
        }
        .onChange(of: aperture) { camera.setAperture(Float(aperture)) }
        .onChange(of: filmID) { applyLook(); flashBadge() }
        .onChange(of: intensity) { applyLook() }
        .onChange(of: grain) { applyLook() }
        .onChange(of: adjustments) { applyLook() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await camera.start() } } else if phase == .background { camera.stop() }
        }
        .onChange(of: pickerItem) { _, item in loadStill(item) }
        .fileImporter(isPresented: $importing, allowedContentTypes: Self.lutTypes, allowsMultipleSelection: true) { result in
            importLUTs(result)
        }
        .sheet(isPresented: $showAbout) { AboutView() }
        .alert("FujiFilm Cam", isPresented: Binding(get: { camera.message != nil }, set: { if !$0 { camera.message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(camera.message ?? "") }
    }

    // MARK: Top bar

    private var topBar: some View {
        HStack(spacing: 22) {
            Button { cycleFlash() } label: { Image(systemName: camera.flash.symbol) }
                .foregroundStyle(camera.flash == .off ? .white : .yellow)
                .disabled(camera.isShowingStill)
            Button { withAnimation(.easeInOut(duration: 0.25)) { frameRatio = frameRatio.next } } label: {
                Text(frameRatio.rawValue)
                    .font(.system(size: 11, weight: .bold))
                    .frame(minWidth: 30)
                    .padding(.horizontal, 5).padding(.vertical, 3)
                    .overlay(RoundedRectangle(cornerRadius: 5).stroke(lineWidth: 1.5))
            }
            .disabled(camera.isShowingStill)
            .accessibilityLabel("Frame size \(frameRatio.rawValue)")
            if camera.portraitSupported && !camera.isShowingStill {
                Button {
                    wantPortrait.toggle()
                    camera.setPortrait(wantPortrait)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "camera.aperture")
                        Text("Portrait").font(.caption.weight(.semibold))
                    }
                }
                .foregroundStyle(camera.isPortrait ? .yellow : .white)
                .accessibilityLabel(camera.isPortrait ? "Portrait on" : "Portrait off")
            }
            Spacer()
            Menu {
                Picker(selection: $grain) {
                    Text("Off").tag(0); Text("Weak").tag(1); Text("Strong").tag(2)
                } label: { Label("Grain", systemImage: "circle.dotted") }
                .pickerStyle(.menu)
                Toggle(isOn: $showGrid) { Label("Grid", systemImage: "grid") }
                Divider()
                Button { importing = true } label: { Label("Import LUT (.cube / Hald PNG)", systemImage: "square.and.arrow.down") }
                Button { showAbout = true } label: { Label("About & credits", systemImage: "info.circle") }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .font(.system(size: 18))
        .foregroundStyle(.white)
        .padding(.horizontal, 20)
        .frame(height: 44)
    }

    // MARK: Preview

    private var preview: some View {
        GeometryReader { geo in
            ZStack {
                FilmPreviewView(frames: camera.frames, fit: camera.isShowingStill)
                if showGrid { GridOverlay() }
                if showFocusUI && !camera.isShowingStill {
                    FocusIndicator(bias: camera.exposureBias, locked: camera.isFocusLocked)
                        .position(x: (camera.focusPoint?.x ?? 0.5) * geo.size.width,
                                  y: (camera.focusPoint?.y ?? 0.5) * geo.size.height)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                }
                statusOverlay
                if flashScreen { Color.black.opacity(0.7) }
            }
            .contentShape(Rectangle())
            // One gesture handles everything so they never fight:
            // tap = focus/expose here · hold = AE/AF lock · drag up/down = exposure · swipe sideways = change look.
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { v in touchChanged(v, size: geo.size) }
                .onEnded { v in touchEnded(v, size: geo.size) })
            .onAppear { camera.setViewfinderSize(geo.size) }
            .onChange(of: geo.size) { _, size in camera.setViewfinderSize(size) }
        }
        .clipped()
    }

    /// Places the viewfinder: Full covers the screen edge to edge (under the bars and the notch);
    /// 3:4 and 9:16 span the full width, just under the top bar when there's room.
    @ViewBuilder private func viewfinder(in size: CGSize) -> some View {
        if let aspect = camera.isShowingStill ? 3 / 4 : frameRatio.aspect {
            let w = min(size.width, size.height * aspect), h = w / aspect
            preview
                .frame(width: w, height: h)
                .offset(y: min(44, max(0, size.height - h)))
                .frame(maxWidth: .infinity)
        } else {
            preview.ignoresSafeArea()
        }
    }

    /// Lock and look badges, under the top bar.
    private var hud: some View {
        VStack(spacing: 0) {
            if camera.isFocusLocked && !camera.isShowingStill {
                Text("AE/AF LOCK")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.yellow, in: RoundedRectangle(cornerRadius: 4))
                    .padding(.top, 8)
            }
            if showBadge {
                VStack(spacing: 2) {
                    Text(film.name).font(.headline)
                    Text(film.note).font(.caption).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.ultraThinMaterial, in: Capsule())
                .padding(.top, 10)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .allowsHitTesting(false)
    }

    /// Zoom buttons / back-to-camera, just above the controls.
    @ViewBuilder private var overlayRow: some View {
        if camera.status == .running && !camera.isShowingStill && !camera.isPortrait && camera.zoomStops.count > 1 {
            zoomPills
        } else if camera.isShowingStill {
            Button { camera.showStill(nil); pickerItem = nil } label: {
                Label("Back to camera", systemImage: "camera.fill")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(.ultraThinMaterial, in: Capsule())
            }
            .padding(.bottom, 12)
        }
    }

    @ViewBuilder private var statusOverlay: some View {
        if !camera.isShowingStill {
            switch camera.status {
            case .denied:
                StatusCard(icon: "camera.fill", title: "Camera access is off",
                           detail: "Allow camera access in Settings, or pick a photo to try the looks.") {
                    Button("Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                }
            case .unavailable:
                StatusCard(icon: "camera.metering.unknown", title: "No camera available",
                           detail: "This device has no camera (e.g. the Simulator). Pick a photo to preview the looks.") { EmptyView() }
            case .failed(let msg):
                StatusCard(icon: "exclamationmark.triangle", title: "Camera error", detail: msg) { EmptyView() }
            default: EmptyView()
            }
        }
    }

    private var zoomPills: some View {
        HStack(spacing: 6) {
            ForEach(camera.zoomStops, id: \.self) { stop in
                let selected = abs(camera.zoom - stop) < 0.05
                Button { camera.setZoom(stop) } label: {
                    Text(stop < 1 ? ".5" : "\(Int(stop.rounded()))" + (selected ? "×" : ""))
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(selected ? .yellow : .white)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(.black.opacity(0.45)))
                }
            }
        }
        .padding(4)
        .background(Capsule().fill(.black.opacity(0.25)))
        .padding(.bottom, 12)
    }

    // MARK: Controls

    /// Panels that open above the shutter; closed by default so the viewfinder stays clear.
    enum Panel { case looks, adjust, depth }

    private var controls: some View {
        let showDepth = camera.isPortrait && !camera.isShowingStill
        return VStack(spacing: 12) {
            if panel == .looks {
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Text("Strength").font(.caption).foregroundStyle(.secondary)
                        Slider(value: $intensity, in: 0...1)
                            .tint(.yellow)
                        Text("\(Int(intensity * 100))%").font(.caption.monospacedDigit()).frame(width: 40, alignment: .trailing)
                    }
                    .padding(.horizontal, 20)
                    .opacity(film.source == .none ? 0.3 : 1)
                    .disabled(film.source == .none)

                    FilmStrip(library: library, selection: $filmID) { film in
                        library.delete(film)
                        if filmID == film.id { filmID = FilmLibrary.original.id }
                    }
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if panel == .adjust {
                adjustPanel.transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if panel == .depth && showDepth {
                HStack(spacing: 12) {
                    Text("Depth").font(.caption).foregroundStyle(.secondary)
                    // Log scale so f/1.4–f/4 (where most of the change happens) gets most of the track.
                    Slider(value: Binding(
                        get: { log(16 / aperture) / log(16 / 1.4) },
                        set: { aperture = (16 * pow(1.4 / 16, $0) * 10).rounded() / 10 }
                    ), in: 0...1)
                    .tint(.yellow)
                    Text(String(format: "f/%.1f", aperture)).font(.caption.monospacedDigit()).frame(width: 40, alignment: .trailing)
                }
                .padding(.horizontal, 20)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            HStack(spacing: 8) {
                PanelChip(title: film.name, open: panel == .looks) { toggle(.looks) }
                    .accessibilityLabel("Look: \(film.name). Show looks")
                PanelChip(title: "Adjust", open: panel == .adjust, marked: !adjustments.isNeutral) { toggle(.adjust) }
                    .accessibilityLabel(adjustments.isNeutral ? "Picture settings" : "Picture settings, changed")
                if showDepth {
                    PanelChip(title: String(format: "f/%.1f", aperture), open: panel == .depth) { toggle(.depth) }
                        .accessibilityLabel("Background blur f/\(String(format: "%.1f", aperture))")
                }
            }

            let lastPhoto = camera.lastPhoto
            HStack {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Group {
                        if let thumb = lastPhoto {
                            Image(uiImage: thumb).resizable().scaledToFill()
                        } else {
                            Image(systemName: "photo.on.rectangle").font(.system(size: 20)).foregroundStyle(.white)
                        }
                    }
                    .frame(width: 50, height: 50)
                    .background(Color.black.opacity(0.35))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white.opacity(0.3)))
                }
                .accessibilityLabel("Pick a photo to apply a look")
                Spacer()
                ShutterButton(busy: camera.isCapturing, label: camera.isShowingStill ? "Save" : nil) { shoot() }
                    .disabled(!(camera.status == .running || camera.isShowingStill))
                Spacer()
                Button { camera.flip() } label: {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 20, weight: .semibold))
                        .frame(width: 50, height: 50)
                        .background(Circle().fill(.black.opacity(0.35)))
                }
                .foregroundStyle(.white)
                .disabled(camera.status != .running || camera.isShowingStill)
                .accessibilityLabel("Switch camera")
            }
            .padding(.horizontal, 28)
        }
        .padding(.top, 10)
        .padding(.bottom, 8)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Fujifilm-style picture settings: tone curve, white balance and Color Chrome.
    private var adjustPanel: some View {
        let signed: (Double) -> String = { v in
            let s = v == v.rounded() ? String(Int(abs(v))) : String(format: "%.1f", abs(v))
            return (v > 0 ? "+" : v < 0 ? "−" : "") + s
        }
        return VStack(spacing: 6) {
            HStack {
                Text("PICTURE SETTINGS").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                Spacer()
                Button("Reset") {
                    adjHighlight = 0; adjShadow = 0; adjKelvin = 5500; adjWBRed = 0; adjWBBlue = 0; adjChrome = 0; adjChromeBlue = 0
                    adjColor = 0; adjDR = 100; adjSharpness = 0; adjClarity = 0
                }
                .font(.subheadline.weight(.semibold)).foregroundStyle(.yellow)
                .disabled(adjustments.isNeutral)
            }
            ChoiceRow(label: "Dynamic range", selection: $adjDR, options: [(100, "DR100"), (200, "DR200"), (400, "DR400")])
            AdjustRow(label: "Highlight", value: $adjHighlight, range: -2...4, step: 0.5, neutral: 0, text: signed)
            AdjustRow(label: "Shadow", value: $adjShadow, range: -2...4, step: 0.5, neutral: 0, text: signed)
            AdjustRow(label: "Color", value: $adjColor, range: -4...4, step: 1, neutral: 0, text: signed)
            AdjustRow(label: "Sharpness", value: $adjSharpness, range: -4...4, step: 1, neutral: 0, text: signed)
            AdjustRow(label: "Clarity", value: $adjClarity, range: -5...5, step: 1, neutral: 0, text: signed)
            AdjustRow(label: "Temperature", value: $adjKelvin, range: 2500...10000, step: 100, neutral: 5500) {
                $0 == 5500 ? "As shot" : "\(Int($0))K"
            }
            AdjustRow(label: "WB shift Red", value: $adjWBRed, range: -9...9, step: 1, neutral: 0, text: signed)
            AdjustRow(label: "WB shift Blue", value: $adjWBBlue, range: -9...9, step: 1, neutral: 0, text: signed)
            ChoiceRow(label: "Color Chrome", selection: $adjChrome)
            ChoiceRow(label: "FX Blue", selection: $adjChromeBlue)
        }
        .padding(.horizontal, 20)
    }

    private func toggle(_ p: Panel) {
        withAnimation(.easeOut(duration: 0.2)) { panel = panel == p ? nil : p }
    }

    // MARK: Touch: focus, exposure, lock, swipe

    private struct Touch {
        enum Axis { case horizontal, vertical }
        var axis: Axis?
        var startBias: Float
        var held = false
    }
    private var liveCamera: Bool { camera.status == .running && !camera.isShowingStill }

    private func touchChanged(_ v: DragGesture.Value, size: CGSize) {
        if touch == nil {
            touch = Touch(startBias: camera.exposureBias)
            guard liveCamera, panel == nil else { return }
            let p = normalized(v.startLocation, size)
            holdTask = Task { @MainActor in
                try? await Task.sleep(for: .seconds(0.55))
                guard !Task.isCancelled, touch?.axis == nil else { return }
                touch?.held = true
                camera.focus(at: p, lock: true)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                revealFocusUI()
            }
        }
        let d = v.translation
        if touch?.axis == nil, hypot(d.width, d.height) > 12 {
            touch?.axis = abs(d.width) > abs(d.height) ? .horizontal : .vertical
            holdTask?.cancel()
        }
        if touch?.axis == .vertical, liveCamera, let start = touch?.startBias {
            camera.setExposureBias(start - Float(d.height / 110))   // ~110 pt per stop, up = brighter
            revealFocusUI()
        }
    }

    private func touchEnded(_ v: DragGesture.Value, size: CGSize) {
        holdTask?.cancel()
        defer { touch = nil }
        guard let t = touch else { return }
        switch t.axis {
        case .horizontal:
            if abs(v.translation.width) > 40 { stepFilm(v.translation.width < 0 ? 1 : -1) }
        case .vertical:
            break
        case nil:
            if panel != nil { withAnimation(.easeOut(duration: 0.2)) { panel = nil }; return }
            guard liveCamera, !t.held else { return }
            camera.focus(at: normalized(v.location, size))
            revealFocusUI()
        }
    }

    private func normalized(_ p: CGPoint, _ size: CGSize) -> CGPoint {
        CGPoint(x: min(max(p.x / size.width, 0), 1), y: min(max(p.y / size.height, 0), 1))
    }

    /// Shows the focus box / exposure slider, then fades it after a moment (kept while locked).
    private func revealFocusUI() {
        withAnimation(.easeOut(duration: 0.15)) { showFocusUI = true }
        focusUIToken += 1
        let token = focusUIToken
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.2))
            guard token == focusUIToken, !camera.isFocusLocked else { return }
            withAnimation(.easeIn(duration: 0.4)) { showFocusUI = false }
        }
    }

    // MARK: Actions

    private func applyLook() {
        let lut = library.lut(for: film)
        FilmRenderer.shared.configure(lut: lut, intensity: Float(intensity), grain: [0, 0.5, 1][grain], adjust: adjustments)
        camera.updateDynamicRange(stops: adjustments.drStops)
    }

    private func flashBadge() {
        withAnimation(.easeOut(duration: 0.2)) { showBadge = true }
        let id = filmID
        Task {
            try? await Task.sleep(for: .seconds(1.4))
            if id == filmID { withAnimation(.easeIn(duration: 0.3)) { showBadge = false } }
        }
    }

    private func stepFilm(_ delta: Int) {
        let all = library.films
        guard let i = all.firstIndex(where: { $0.id == filmID }) else { return }
        filmID = all[(i + delta + all.count) % all.count].id
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func cycleFlash() {
        let all = CameraController.Flash.allCases
        camera.flash = all[(all.firstIndex(of: camera.flash)! + 1) % all.count]
    }

    private func shoot() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        if !camera.isShowingStill {
            flashScreen = true
            withAnimation(.easeOut(duration: 0.25)) { flashScreen = false }
        }
        camera.capture()
    }

    private func loadStill(_ item: PhotosPickerItem?) {
        guard let item else { return }
        Task {
            guard let data = try? await item.loadTransferable(type: Data.self),
                  let image = CIImage(data: data, options: [.applyOrientationProperty: true]) else {
                camera.message = "Couldn't open that photo."
                return
            }
            camera.showStill(image)
        }
    }

    static let lutTypes: [UTType] = [UTType(filenameExtension: "cube") ?? .data, .png, .tiff]

    private func importLUTs(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            var errors: [String] = []
            var last: Film?
            for url in urls {
                do { last = try library.importLUT(from: url) }
                catch { errors.append("\(url.lastPathComponent): \(error.localizedDescription)") }
            }
            if let last { filmID = last.id }
            if !errors.isEmpty { camera.message = errors.joined(separator: "\n") }
        case .failure(let error):
            camera.message = error.localizedDescription
        }
    }
}

// MARK: - Pieces

private struct GridOverlay: View {
    var body: some View {
        GeometryReader { g in
            Path { p in
                for i in 1...2 {
                    let x = g.size.width * CGFloat(i) / 3, y = g.size.height * CGFloat(i) / 3
                    p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: g.size.height))
                    p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: g.size.width, y: y))
                }
            }
            .stroke(.white.opacity(0.35), lineWidth: 0.5)
        }
        .allowsHitTesting(false)
    }
}

/// Yellow focus square with an exposure slider (sun) beside it, like the system Camera app.
private struct FocusIndicator: View {
    let bias: Float
    let locked: Bool
    private let track: CGFloat = 110

    var body: some View {
        let range = CameraController.exposureRange
        let t = CGFloat((bias - range.lowerBound) / (range.upperBound - range.lowerBound))   // 0…1
        ZStack {
            RoundedRectangle(cornerRadius: 3)
                .stroke(.yellow, lineWidth: locked ? 2 : 1.5)
                .frame(width: 76, height: 76)
            VStack(spacing: 0) {
                Rectangle().fill(.yellow.opacity(0.85)).frame(width: 1, height: max(0, (1 - t) * track - 12))
                Image(systemName: "sun.max.fill").font(.system(size: 15)).foregroundStyle(.yellow).frame(height: 24)
                Rectangle().fill(.yellow.opacity(0.85)).frame(width: 1, height: max(0, t * track - 12))
            }
            .frame(height: track)
            .offset(x: 56)
            if abs(bias) >= 0.05 {
                Text(String(format: "%+.1f", bias))
                    .font(.system(size: 11, weight: .semibold).monospacedDigit())
                    .foregroundStyle(.yellow)
                    .offset(x: 56, y: track / 2 + 12)
            }
        }
        .shadow(color: .black.opacity(0.4), radius: 2)
    }
}

private struct ShutterButton: View {
    let busy: Bool
    let label: String?
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().stroke(.white, lineWidth: 4).frame(width: 74, height: 74)
                Circle().fill(.white).frame(width: 62, height: 62).scaleEffect(busy ? 0.85 : 1)
                if busy { ProgressView().tint(.black) }
                else if let label { Text(label).font(.caption.bold()).foregroundStyle(.black) }
            }
            .animation(.spring(duration: 0.2), value: busy)
        }
        .disabled(busy)
        .accessibilityLabel(label ?? "Take photo")
    }
}

private struct StatusCard<Actions: View>: View {
    let icon: String, title: String, detail: String
    @ViewBuilder let actions: () -> Actions
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 34))
            Text(title).font(.headline)
            Text(detail).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            actions()
        }
        .padding(24)
        .frame(maxWidth: 300)
    }
}

/// Small capsule above the shutter that opens a panel (looks, background blur).
private struct PanelChip: View {
    let title: String
    let open: Bool
    var marked = false      // shows a dot (e.g. picture settings changed)
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Text(title).font(.caption.weight(.semibold)).lineLimit(1)
                if marked { Circle().fill(.yellow).frame(width: 5, height: 5) }
                Image(systemName: open ? "chevron.down" : "chevron.up").font(.system(size: 9, weight: .bold))
            }
            .foregroundStyle(open ? .yellow : .white)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(.black.opacity(0.45), in: Capsule())
        }
    }
}

/// A labelled slider with its value; the value turns yellow when changed from neutral.
private struct AdjustRow: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let neutral: Double
    let text: (Double) -> String
    var body: some View {
        HStack(spacing: 12) {
            Text(label).font(.caption).foregroundStyle(.secondary).frame(width: 92, alignment: .leading)
            Slider(value: $value, in: range, step: step).tint(.yellow)
            Text(text(value)).font(.caption.monospacedDigit())
                .foregroundStyle(value == neutral ? .white : .yellow)
                .frame(width: 52, alignment: .trailing)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Off / Weak / Strong, like Fujifilm's Color Chrome menus.
private struct ChoiceRow: View {
    let label: String
    @Binding var selection: Int
    var options: [(Int, String)] = [(0, "Off"), (1, "Weak"), (2, "Strong")]
    var body: some View {
        HStack(spacing: 12) {
            Text(label).font(.caption).foregroundStyle(.secondary).frame(width: 92, alignment: .leading)
            Picker(label, selection: $selection) {
                ForEach(options, id: \.0) { Text($0.1).tag($0.0) }
            }
            .pickerStyle(.segmented)
        }
    }
}
