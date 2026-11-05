import SwiftUI

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("How it works") {
                    Text("Every look is a 3D colour lookup table (33×33×33) applied on the GPU to each camera frame in real time, and again to the full-resolution photo when you press the shutter. Strength blends the look with the original; grain is added after the colour.")
                }
                Section("Camera looks") {
                    Text("Velvia, Astia, Classic Chrome, Classic Neg., Nostalgic Neg., Reala Ace, Pro Neg. Std/Hi, Eterna, Bleach Bypass and Acros are colour LUTs fitted for this app to Fujifilm's published sample photos, measuring how each simulation changes colour relative to Provia. Provia is a mild built-in recipe. They capture colour and tone only (no grain or sharpening), work best on well-exposed photos, and are approximations, not Fujifilm's own processing.")
                }
                Section("Film stocks") {
                    Text("Film-stock looks are adapted from the RawTherapee Film Simulation Collection (2015-09-20) by Pat David, Pavlov Dmitry and Michael Ezra, licensed CC BY-SA 4.0. Changes: the original sRGB Hald CLUTs were resampled with trilinear interpolation to 33³ samples and stored as 16-bit binaries. The adapted LUTs are shared under the same licence.")
                    Link("CC BY-SA 4.0 licence", destination: URL(string: "https://creativecommons.org/licenses/by-sa/4.0/")!)
                    Link("Source collection", destination: URL(string: "https://github.com/cedeber/hald-clut")!)
                }
                Section("Your own LUTs") {
                    Text("Use ••• › Import LUT to add any .cube file or Hald CLUT image (e.g. 512×512 level 8 or 1728×1728 level 12). Long-press an imported look to remove it.")
                }
                Section {
                    Text("Fujifilm and the film names are trademarks of FUJIFILM Corporation, used only to describe the look each preset aims for. This app is not affiliated with or endorsed by Fujifilm.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("About")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
