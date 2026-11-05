// Exports every look as a 33³ RGB8 LUT for the web app (red fastest, then green, then blue).
// Build & run from the repo root:
//   swiftc -O -o /tmp/export-luts tools/export-web-luts/main.swift FujiFilmCam/Engine/LUT.swift FujiFilmCam/Engine/LookRecipe.swift
//   /tmp/export-luts FujiFilmCam/LUTs web/luts
import Foundation

let args = CommandLine.arguments
let bundledDir = URL(fileURLWithPath: args[1]), outDir = URL(fileURLWithPath: args[2])
let recipes: [(String, LookRecipe)] = [
    ("provia", .provia), ("velvia", .velvia), ("astia", .astia), ("classic-chrome", .classicChrome),
    ("classic-neg", .classicNeg), ("nostalgic-neg", .nostalgicNeg), ("reala-ace", .realaAce),
    ("pro-neg-std", .proNegStd), ("pro-neg-hi", .proNegHi), ("eterna", .eterna),
    ("eterna-bleach", .eternaBleach), ("acros", .acros),
]
func write(_ lut: LUT, _ name: String) throws {
    let bytes = lut.values.map { UInt8(max(0, min(255, ($0 * 255).rounded()))) }
    try Data(bytes).write(to: outDir.appendingPathComponent("\(name).bin"))
}
for (name, r) in recipes { try write(r.makeLUT(), name) }
for file in try FileManager.default.contentsOfDirectory(atPath: bundledDir.path) where file.hasSuffix(".lut") {
    let lut = try LUT(bundledData: Data(contentsOf: bundledDir.appendingPathComponent(file)))
    try write(lut, "s-" + file.replacingOccurrences(of: ".lut", with: ""))
}
print("exported to \(outDir.path)")
