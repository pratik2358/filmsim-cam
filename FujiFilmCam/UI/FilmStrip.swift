import SwiftUI

/// Horizontal picker of looks, grouped, each with a swatch of reference colours run through its LUT.
struct FilmStrip: View {
    let library: FilmLibrary
    @Binding var selection: String
    var onDelete: (Film) -> Void

    @State private var swatches: [String: [Color]] = [:]

    /// Skin, sky, foliage, red, shadow-ish grey, highlight-ish grey.
    private static let references: [SIMD3<Float>] = [
        SIMD3(0.87, 0.67, 0.55), SIMD3(0.45, 0.66, 0.90), SIMD3(0.33, 0.55, 0.24),
        SIMD3(0.80, 0.18, 0.16), SIMD3(0.25, 0.25, 0.25), SIMD3(0.85, 0.85, 0.85),
    ]

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 10) {
                    ForEach(Film.Group.allCases, id: \.self) { group in
                        let films = library.films(in: group)
                        if !films.isEmpty {
                            Text(group.rawValue.uppercased())
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.secondary)
                                .rotationEffect(.degrees(-90))
                                .fixedSize()
                                .frame(width: 14)
                            ForEach(films) { film in
                                tile(film).id(film.id)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .frame(height: 92)
            .onAppear { proxy.scrollTo(selection, anchor: .center) }
            .onChange(of: selection) { _, id in withAnimation { proxy.scrollTo(id, anchor: .center) } }
        }
    }

    private func tile(_ film: Film) -> some View {
        let selected = film.id == selection
        return Button { selection = film.id } label: {
            VStack(spacing: 5) {
                swatch(film)
                    .frame(width: 58, height: 58)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? .yellow : .white.opacity(0.15), lineWidth: selected ? 2.5 : 1))
                Text(film.name)
                    .font(.system(size: 10, weight: selected ? .bold : .medium))
                    .foregroundStyle(selected ? .yellow : .white)
                    .lineLimit(1)
                    .frame(width: 66)
            }
        }
        .buttonStyle(.plain)
        .contextMenu {
            if film.group == .custom {
                Button(role: .destructive) { onDelete(film) } label: { Label("Remove", systemImage: "trash") }
            }
        }
        .task(id: film.id) { computeSwatch(film) }
    }

    @ViewBuilder private func swatch(_ film: Film) -> some View {
        if film.source == .none {
            ZStack {
                Color.white.opacity(0.08)
                Image(systemName: "circle.slash").foregroundStyle(.secondary)
            }
        } else if let colors = swatches[film.id] {
            VStack(spacing: 0) {
                HStack(spacing: 0) { ForEach(0..<3, id: \.self) { colors[$0] } }
                HStack(spacing: 0) { ForEach(3..<6, id: \.self) { colors[$0] } }
            }
        } else {
            Color.white.opacity(0.08)
        }
    }

    private func computeSwatch(_ film: Film) {
        guard swatches[film.id] == nil, let lut = library.lut(for: film) else { return }
        swatches[film.id] = Self.references.map { c in
            let o = lut.sample(c)
            return Color(.sRGB, red: Double(o.x), green: Double(o.y), blue: Double(o.z))
        }
    }
}
