import Foundation
import Observation
import KeyguardShared

/// The WebDAV and S3 settings sheets a shared producer opened. Every call echoes the sheet id, so a
/// dismissed sheet never edits its replacement.
@MainActor
protocol RemoteSettingsSource: AnyObject {
    func setWebDavField(sessionId: String, id: String, text: String)
    func submitWebDavSettings(sessionId: String)
    func testWebDavConnection(sessionId: String)
    func cancelWebDavSettings()
    func setS3Field(sessionId: String, id: String, text: String)
    func blurS3Field(sessionId: String, id: String)
    func setS3PathStyle(sessionId: String, value: Bool)
    func submitS3Settings(sessionId: String)
    func testS3Connection(sessionId: String)
    func cancelS3Settings()
}

extension RemoteSettingsSheets: RemoteSettingsSource {}

/// The open WebDAV and S3 settings sheets of one presentation: the remote database of a KeePass
/// sign-in, or the backup location of an import.
@MainActor
@Observable
final class RemoteSettingsModel {
    @ObservationIgnored private let source: any RemoteSettingsSource

    @ObservationIgnored private var dismissedWebDavId: String?
    private(set) var webDav: WebDavSettingsSnapshot?

    @ObservationIgnored private var dismissedS3Id: String?
    private(set) var s3: S3SettingsSnapshot?

    init(source: any RemoteSettingsSource) {
        self.source = source
    }

    /// A sheet dismissed here stays dismissed, even if its producer publishes it once more.
    func receiveWebDav(_ snapshot: WebDavSettingsSnapshot?) {
        if let snapshot, snapshot.id == dismissedWebDavId { return }
        webDav = snapshot
    }

    func receiveS3(_ snapshot: S3SettingsSnapshot?) {
        if let snapshot, snapshot.id == dismissedS3Id { return }
        s3 = snapshot
    }

    func clear() {
        webDav = nil
        s3 = nil
    }

    /// `id` is "url", "username" or "password".
    func setWebDavField(sessionId: String, id: String, text: String) {
        source.setWebDavField(sessionId: sessionId, id: id, text: text)
    }

    /// Validates and saves the WebDAV settings; the producer then delivers the location to
    /// whoever opened the sheet, and the sheet dismisses via `webDav`.
    func submitWebDavSettings(sessionId: String) {
        source.submitWebDavSettings(sessionId: sessionId)
    }

    /// Validates the settings and pings the server; the result arrives as a toast.
    func testWebDavConnection(sessionId: String) {
        source.testWebDavConnection(sessionId: sessionId)
    }

    func dismissWebDav() {
        dismissedWebDavId = webDav?.id
        webDav = nil
        source.cancelWebDavSettings()
    }

    /// `id` is "endpoint", "region", "bucket", "key", "accessKeyId" or "secretAccessKey".
    func setS3Field(sessionId: String, id: String, text: String) {
        source.setS3Field(sessionId: sessionId, id: id, text: text)
    }

    func blurS3Field(sessionId: String, id: String) {
        source.blurS3Field(sessionId: sessionId, id: id)
    }

    func setS3PathStyle(sessionId: String, value: Bool) {
        source.setS3PathStyle(sessionId: sessionId, value: value)
    }

    /// Validates and saves the S3 settings; the producer then delivers the location to whoever
    /// opened the sheet, and the sheet dismisses via `s3`.
    func submitS3Settings(sessionId: String) {
        source.submitS3Settings(sessionId: sessionId)
    }

    /// Validates the settings and reads the object (or probes its folder); the result arrives as a toast.
    func testS3Connection(sessionId: String) {
        source.testS3Connection(sessionId: sessionId)
    }

    func dismissS3() {
        dismissedS3Id = s3?.id
        s3 = nil
        source.cancelS3Settings()
    }
}
