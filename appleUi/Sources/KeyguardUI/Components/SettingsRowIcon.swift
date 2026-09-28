import SwiftUI

/// Keeps category tiles and account selection indicators in the same icon area.
struct SettingsRowIcon: View {
    let systemName: String
    let color: AnyShapeStyle
    var isTile = true

    #if os(macOS)
    @ScaledMetric(relativeTo: .body) private var iconSize = 24
    #else
    @ScaledMetric(relativeTo: .body) private var iconSize = 30
    #endif

    // Decorative icons stop growing before accessibility text sizes so long
    // labels retain room to wrap.
    private var tileSize: CGFloat { min(iconSize, 40) }

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: tileSize * 0.55, weight: .medium))
            .symbolRenderingMode(.monochrome)
            .foregroundStyle(isTile ? AnyShapeStyle(.white) : color)
            .frame(width: tileSize, height: tileSize)
            .background {
                if isTile {
                    RoundedRectangle(cornerRadius: tileSize / 4)
                        .fill(color)
                }
            }
    }
}
