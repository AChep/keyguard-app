import SwiftUI

extension View {
    /// Native row insets for content that already supplies its own control hit areas.
    @ViewBuilder
    func compactControlRowInsets(_ vertical: CGFloat = 0) -> some View {
        #if os(iOS)
        if #available(iOS 26.0, *) {
            listRowInsets(.vertical, vertical)
        } else {
            // Earlier SwiftUI versions only accept all four edges. Retain the
            // grouped form's standard horizontal content inset on those systems.
            listRowInsets(EdgeInsets(top: vertical, leading: 20, bottom: vertical, trailing: 20))
        }
        #else
        self
        #endif
    }
}
