import SwiftUI
import KeyguardShared

/// Reuses the regular screen and its observation lifecycle; result activation
/// never opens a confirmation sheet or executes the indexed action.
struct SettingsSearchDestination: View {
    @Environment(SettingsModel.self) private var settingsModel
    let entry: SettingsSearchEntrySnapshot
    @State private var request: SettingsRevealRequest?

    var body: some View {
        if let item = settingsModel.settings.items.first(where: { $0.id == entry.categoryId }) {
            SettingsSubroute(item: item)
                .environment(\.settingsRevealRequest, request)
                .onAppear { request = entry.target.map { SettingsRevealRequest(target: $0) } }
        }
    }
}
