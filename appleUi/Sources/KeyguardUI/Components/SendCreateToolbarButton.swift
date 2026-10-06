#if os(macOS)
import SwiftUI

struct SendCreateToolbarButton: View {
    let needsAccount: Bool
    @Binding var showingAddItem: Bool

    var body: some View {
        Button {
            showingAddItem = true
        } label: {
            Label(L10n.addsendHeaderNewTitle, systemImage: "plus")
        }
        .help(L10n.textActionSendTitle)
        .accessibilityLabel(L10n.textActionSendTitle)
        .disabled(needsAccount)
    }
}
#endif
