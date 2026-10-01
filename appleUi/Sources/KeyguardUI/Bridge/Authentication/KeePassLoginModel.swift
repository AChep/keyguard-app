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

    private(set) var keepass: KeePassLoginSnapshot = KeePassLoginSnapshot.companion.empty

    private(set) var keepassDidSucceed = false

    private(set) var keepassWebDav: WebDavSettingsSnapshot?

    @ObservationIgnored private var keepassLoginSubscription: BridgeObservation?

    /// Call when the KeePass login screen appears; balance with
    /// `stopKeePassLoginObservation()`.
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

    /// Selecting a tab makes the shared producer launch the matching file picker.
    func selectKeePassTab(key: String) {
        core.selectKeePassTab(key: key)
    }

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

    func pickKeePassKeyFile() {
        core.pickKeePassKeyFile()
    }

    func clearKeePassKeyFile() {
        core.clearKeePassKeyFile()
    }

    func setKeePassPassword(text: String) {
        core.setKeePassPassword(text: text)
    }

    func submitKeePassLogin() {
        core.submitKeePassLogin()
    }

    /// `id` is "url", "username" or "password".
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

    func dismissWebDavSettings() {
        keepassWebDav = nil
        core.cancelWebDavSettings()
    }
}
