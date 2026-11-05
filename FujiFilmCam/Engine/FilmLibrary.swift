import Foundation
import Observation

struct Film: Identifiable, Hashable, Sendable {
    enum Source: Hashable, Sendable {
        case none
        case recipe(String)        // key into FilmLibrary.recipes
        case bundled(String)       // resource name in LUTs/ (.lut)
        case imported(String)      // file name in Application Support/LUTs
    }
    enum Group: String, CaseIterable, Sendable {
        case camera = "Camera looks"
        case stock = "Film stocks"
        case custom = "Imported"
    }

    let id: String
    let name: String
    let note: String
    let group: Group
    let source: Source
    var isMono = false
}

@MainActor
@Observable
final class FilmLibrary {
    private(set) var films: [Film] = []
    private var cache: [String: LUT] = [:]

    static let recipes: [String: LookRecipe] = [
        "provia": .provia, "velvia": .velvia, "astia": .astia,
        "classic-chrome": .classicChrome, "classic-neg": .classicNeg, "nostalgic-neg": .nostalgicNeg,
        "reala-ace": .realaAce, "pro-neg-std": .proNegStd, "pro-neg-hi": .proNegHi,
        "eterna": .eterna, "eterna-bleach": .eternaBleach, "acros": .acros,
    ]

    static let original = Film(id: "original", name: "Original", note: "No look applied", group: .camera, source: .none)

    private static let builtIn: [Film] = [
        original,
        Film(id: "provia", name: "Provia", note: "Standard, faithful colour", group: .camera, source: .recipe("provia")),
        Film(id: "velvia", name: "Velvia", note: "Vivid, deep landscapes", group: .camera, source: .recipe("velvia")),
        Film(id: "astia", name: "Astia", note: "Soft, flattering skin", group: .camera, source: .recipe("astia")),
        Film(id: "classic-chrome", name: "Classic Chrome", note: "Muted documentary tones", group: .camera, source: .recipe("classic-chrome")),
        Film(id: "classic-neg", name: "Classic Neg.", note: "Hard tone, cyan shadows", group: .camera, source: .recipe("classic-neg")),
        Film(id: "nostalgic-neg", name: "Nostalgic Neg.", note: "Amber highlights, warm", group: .camera, source: .recipe("nostalgic-neg")),
        Film(id: "reala-ace", name: "Reala Ace", note: "Natural, true-to-life", group: .camera, source: .recipe("reala-ace")),
        Film(id: "pro-neg-std", name: "Pro Neg. Std", note: "Soft portrait tonality", group: .camera, source: .recipe("pro-neg-std")),
        Film(id: "pro-neg-hi", name: "Pro Neg. Hi", note: "Punchier portraits", group: .camera, source: .recipe("pro-neg-hi")),
        Film(id: "eterna", name: "Eterna", note: "Cinematic, understated", group: .camera, source: .recipe("eterna")),
        Film(id: "eterna-bleach", name: "Bleach Bypass", note: "Desaturated, high contrast", group: .camera, source: .recipe("eterna-bleach")),
        Film(id: "acros", name: "Acros", note: "Fine black & white", group: .camera, source: .recipe("acros"), isMono: true),

        Film(id: "s-provia-100f", name: "Provia 100F", note: "Slide film", group: .stock, source: .bundled("provia-100f")),
        Film(id: "s-velvia-50", name: "Velvia 50", note: "Slide film", group: .stock, source: .bundled("velvia-50")),
        Film(id: "s-velvia-100", name: "Velvia 100", note: "Slide film", group: .stock, source: .bundled("velvia-100")),
        Film(id: "s-astia-100f", name: "Astia 100F", note: "Slide film", group: .stock, source: .bundled("astia-100f")),
        Film(id: "s-sensia-100", name: "Sensia 100", note: "Slide film", group: .stock, source: .bundled("sensia-100")),
        Film(id: "s-pro-160c", name: "Pro 160C", note: "Portrait negative", group: .stock, source: .bundled("pro-160c")),
        Film(id: "s-pro-400h", name: "Pro 400H", note: "Portrait negative", group: .stock, source: .bundled("pro-400h")),
        Film(id: "s-pro-800z", name: "Pro 800Z", note: "Portrait negative", group: .stock, source: .bundled("pro-800z")),
        Film(id: "s-superia-200", name: "Superia 200", note: "Consumer negative", group: .stock, source: .bundled("superia-200")),
        Film(id: "s-superia-400", name: "Superia 400", note: "Consumer negative", group: .stock, source: .bundled("superia-400")),
        Film(id: "s-reala-100", name: "Superia Reala 100", note: "Consumer negative", group: .stock, source: .bundled("reala-100")),
        Film(id: "s-xtra-800", name: "Superia X-Tra 800", note: "Consumer negative", group: .stock, source: .bundled("xtra-800")),
        Film(id: "s-fp-100c", name: "FP-100C", note: "Instant peel-apart", group: .stock, source: .bundled("fp-100c")),
        Film(id: "s-acros-100", name: "Neopan Acros 100", note: "B&W negative", group: .stock, source: .bundled("acros-100"), isMono: true),
        Film(id: "s-neopan-1600", name: "Neopan 1600", note: "B&W negative", group: .stock, source: .bundled("neopan-1600"), isMono: true),
    ]

    init() { reload() }

    func films(in group: Film.Group) -> [Film] { films.filter { $0.group == group } }

    func film(id: String) -> Film? { films.first { $0.id == id } }

    /// Returns the LUT for a film (nil for Original). Results are cached.
    func lut(for film: Film) -> LUT? {
        if let hit = cache[film.id] { return hit }
        let lut: LUT?
        switch film.source {
        case .none: lut = nil
        case .recipe(let key): lut = Self.recipes[key]?.makeLUT()
        case .bundled(let name):
            lut = Bundle.main.url(forResource: name, withExtension: "lut")
                .flatMap { try? Data(contentsOf: $0) }
                .flatMap { try? LUT(bundledData: $0) }
        case .imported(let file):
            lut = try? LUT.load(contentsOf: Self.importDirectory.appendingPathComponent(file))
        }
        if let lut { cache[film.id] = lut }
        return lut
    }

    // MARK: Imports

    static var importDirectory: URL {
        let dir = URL.applicationSupportDirectory.appendingPathComponent("LUTs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Copies a user-chosen .cube / Hald PNG into the app, validating it first.
    @discardableResult
    func importLUT(from url: URL) throws -> Film {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let lut = try LUT.load(contentsOf: url)
        let ext = url.pathExtension.lowercased()
        let base = url.deletingPathExtension().lastPathComponent
        var file = "\(base).\(ext)", n = 2
        while FileManager.default.fileExists(atPath: Self.importDirectory.appendingPathComponent(file).path) {
            file = "\(base) \(n).\(ext)"; n += 1
        }
        try FileManager.default.copyItem(at: url, to: Self.importDirectory.appendingPathComponent(file))
        reload()
        let film = films.first { $0.source == .imported(file) }!
        cache[film.id] = lut
        return film
    }

    func delete(_ film: Film) {
        guard case .imported(let file) = film.source else { return }
        try? FileManager.default.removeItem(at: Self.importDirectory.appendingPathComponent(file))
        cache[film.id] = nil
        reload()
    }

    private func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(atPath: Self.importDirectory.path)) ?? []
        let imported = files.sorted().filter { ["cube", "png", "tif", "tiff"].contains(($0 as NSString).pathExtension.lowercased()) }
            .map { file in
                Film(id: "u-\(file)", name: (file as NSString).deletingPathExtension, note: "Imported LUT",
                     group: .custom, source: .imported(file))
            }
        films = Self.builtIn + imported
    }
}
