import SwiftUI

struct BackupLocationLabel: View {
    /// The bridge store kind: "local", "webdav" or "s3".
    let kind: String
    let location: String?

    var body: some View {
        LabeledContent(kind == "local" ? L10n.folder : L10n.server, value: displayName)
            .help(location ?? L10n.prefItemAutomaticBackupsNotConfigured)
    }

    private var displayName: String {
        guard let location, !location.isEmpty else { return L10n.prefItemAutomaticBackupsNotConfigured }
        if kind == "local", let url = URL(string: location), url.isFileURL, !url.lastPathComponent.isEmpty {
            return url.lastPathComponent
        }
        return location
    }

    /// The location of the `kind` destination. An S3 destination is formatted like the Android and desktop
    /// apps: `s3://bucket/prefix at host`.
    static func location(
        kind: String, localPath: String?, webDavUrl: String?, s3Location: String?, s3EndpointHost: String?
    ) -> String? {
        switch kind {
        case "webdav":
            return webDavUrl
        case "s3":
            guard let s3Location else { return nil }
            return s3EndpointHost.map { L10n.s3LocationSummary(s3Location, $0) } ?? s3Location
        default:
            return localPath
        }
    }
}
