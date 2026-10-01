import Foundation

/// Dimensions of the main iPad generator, measured inside its navigation container.
struct GeneratorWorkspaceLayout: Equatable {
    static let breakpoint: CGFloat = 840
    static let maximumWidth: CGFloat = 1_200
    static let margin: CGFloat = 24
    static let spacing: CGFloat = 24

    var settingsWidth: CGFloat?
    var isWide: Bool { settingsWidth != nil }

    static let compact = GeneratorWorkspaceLayout()

    init(
        width: CGFloat = 0,
        isPad: Bool = false,
        isRegular: Bool = false,
        isAccessibilitySize: Bool = false,
        textScale: CGFloat = 1
    ) {
        let scale = max(1, textScale)
        guard isPad, isRegular, !isAccessibilitySize, width >= Self.breakpoint * scale else { return }
        let interior = min(width, Self.maximumWidth) - Self.margin * 2 - Self.spacing
        settingsWidth = min(max(interior * 0.4, 360 * scale), 440 * scale)
    }
}
