import SwiftUI

/// Semantic backgrounds for grouped content outside a native List or Form.
enum GroupedSurfaceStyle {
    static var background: Color {
        #if os(macOS)
        Color(platform: .windowBackgroundColor)
        #else
        Color(platform: .systemGroupedBackground)
        #endif
    }

    static var content: some ShapeStyle {
        #if os(macOS)
        // Matches native grouped forms; controlBackgroundColor can blend into
        // the window background, especially in Dark Mode.
        BackgroundStyle().secondary
        #else
        Color(platform: .secondarySystemGroupedBackground)
        #endif
    }
}
