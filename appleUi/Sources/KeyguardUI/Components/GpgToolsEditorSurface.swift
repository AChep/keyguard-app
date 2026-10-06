import SwiftUI

/// Keeps an empty text editor visibly editable inside a grouped form or sheet.
struct GpgToolsEditorSurface: ViewModifier {
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background(Color(platform: .platformControlBackground), in: RoundedRectangle(cornerRadius: 8))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(Color(platform: .platformSeparator), lineWidth: 1)
                    .allowsHitTesting(false)
            }
    }
}
