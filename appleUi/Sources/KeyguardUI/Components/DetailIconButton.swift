import SwiftUI

/// Shared sizing and accessibility for a detail row's independent shortcuts.
struct DetailIconButton: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .labelStyle(.iconOnly)
                .touchTarget()
        }
        .buttonStyle(.borderless)
        .help(title)
    }
}
