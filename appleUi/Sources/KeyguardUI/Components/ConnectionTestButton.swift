import SwiftUI

/// Tests the connection of a remote settings sheet; shows progress while a test runs.
struct ConnectionTestButton: View {
    let isTesting: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if isTesting {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(L10n.webdavSettingsTestTitle)
            }
        }
        .accessibilityLabel(L10n.webdavSettingsTestTitle)
        .disabled(isTesting)
    }
}
