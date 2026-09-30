import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class KeePassLoginModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// Current KeePass login state.
    private(set) var keepass: KeePassLoginSnapshot = KeePassLoginSnapshot.companion.empty

    /// Flips to `true` once the shared producer reports the account was added,
    /// so the KeePass login view can dismiss itself.
    private(set) var keepassDidSucceed = false

    /// WebDAV sub-form state for the KeePass add-account flow.
    private(set) var keepassWebDav: WebDavSettingsSnapshot?

    @ObservationIgnored private var keepassLoginSubscription: BridgeObservation?

    /// Starts running the shared KeePass add-account producer. Call when the
    /// KeePass login screen appears; balance with `stopKeePassLoginObservation()`
    /// on disappear.
    func startKeePassLoginObservation() {
        startObservation(\.keepassLoginSubscription) { deliver in
            BridgeObservation(
                core.observeKeePassLogin(
                    onChange: { snapshot in
                        deliver { $0.keepass = snapshot }
                    },
                    onSuccess: {
                        deliver { $0.keepassDidSucceed = true }
                    },
                    onWebDavChange: { snapshot in
                        deliver { $0.keepassWebDav = snapshot }
                    }
                ))
        }
    }

    func stopKeePassLoginObservation() {
        stopObservation(\.keepassLoginSubscription)
        keepass = KeePassLoginSnapshot.companion.empty
        keepassDidSucceed = false
        keepassWebDav = nil
    }

    /// Selects a KeePass mode tab ("open" / "new"); the shared producer then
    /// launches the matching file picker (see `presentKeePassFilePicker`).
    func selectKeePassTab(key: String) {
        core.selectKeePassTab(key: key)
    }

    /// Selects the database location ("local" / "webdav") by its snapshot key.
    /// Selecting WebDAV surfaces the settings sheet through `keepassWebDav`.
    func selectKeePassLocation(key: String) {
        core.selectKeePassLocation(key: key)
    }

    /// Re-picks the database file (or re-opens the WebDAV form for a WebDAV location).
    func pickKeePassDbFile() {
        core.pickKeePassDbFile()
    }

    func clearKeePassDbFile() {
        core.clearKeePassDbFile()
    }

    /// Picks the optional key file.
    func pickKeePassKeyFile() {
        core.pickKeePassKeyFile()
    }

    func clearKeePassKeyFile() {
        core.clearKeePassKeyFile()
    }

    /// Forwards typed text into the shared KeePass master-password field.
    func setKeePassPassword(text: String) {
        core.setKeePassPassword(text: text)
    }

    /// Submits the KeePass add-account form held by the shared producer.
    func submitKeePassLogin() {
        core.submitKeePassLogin()
    }

    /// Forwards typed text into a WebDAV settings field: "url" / "username" / "password".
    func setWebDavField(id: String, text: String) {
        core.setWebDavField(id: id, text: text)
    }

    /// Validates and saves the WebDAV settings; the producer then delivers the
    /// location to the KeePass form and the sheet dismisses via `keepassWebDav`.
    func submitWebDavSettings() {
        core.submitWebDavSettings()
    }

    /// Validates the settings and pings the server; the result arrives as a toast.
    func testWebDavConnection() {
        core.testWebDavConnection()
    }

    /// Dismisses the WebDAV settings sheet without saving.
    func dismissWebDavSettings() {
        keepassWebDav = nil
        core.cancelWebDavSettings()
    }
}
