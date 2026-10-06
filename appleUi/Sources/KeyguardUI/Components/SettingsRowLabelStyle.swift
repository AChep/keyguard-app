import SwiftUI

/// Centers the icon against the whole label, including an account's subtitle.
struct SettingsRowLabelStyle: LabelStyle {
    #if os(macOS)
    private let spacing: CGFloat = 8
    #else
    private let spacing: CGFloat = 16
    #endif

    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .center, spacing: spacing) {
            configuration.icon
            configuration.title
        }
    }
}
