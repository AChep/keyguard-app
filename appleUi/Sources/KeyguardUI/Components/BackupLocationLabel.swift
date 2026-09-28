import SwiftUI

struct BackupLocationLabel: View {
    let isWebDav: Bool
    let location: String?

    var body: some View {
        LabeledContent(isWebDav ? L10n.server : L10n.folder, value: displayName)
            .help(location ?? L10n.prefItemAutomaticBackupsNotConfigured)
    }

    private var displayName: String {
        guard let location, !location.isEmpty else { return L10n.prefItemAutomaticBackupsNotConfigured }
        if !isWebDav, let url = URL(string: location), url.isFileURL, !url.lastPathComponent.isEmpty {
            return url.lastPathComponent
        }
        return location
    }
}
