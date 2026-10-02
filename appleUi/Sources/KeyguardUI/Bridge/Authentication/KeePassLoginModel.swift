import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class KeePassLoginModel: SnapshotObserving {
    private let source: any KeePassLoginSource
    let filePicker = FilePickerSession()
    /// Cancelled once the form is dismissed or signs in; the source is then never observed again.
    @ObservationIgnored private let lifetime: BridgeObservation

    init(source: any KeePassLoginSource) {
        self.source = source
        lifetime = BridgeObservation(cancel: { source.close() })
    }

    private(set) var keepass: KeePassLoginSnapshot = KeePassLoginSnapshot.companion.empty

    private(set) var keepassDidSucceed = false

    @ObservationIgnored private var dismissedWebDavId: String?
    private(set) var keepassWebDav: WebDavSettingsSnapshot?

    @ObservationIgnored private var keepassLoginSubscription: BridgeObservation?

    /// Call when the KeePass login screen appears; balance with
    /// `stopKeePassLoginObservation()`.
    func startKeePassLoginObservation() {
        guard !lifetime.isCancelled else { return }
        startObservation(\.keepassLoginSubscription) { deliver in
            source.setKeePassFilePickerRequestHandler { [weak self] request in
                deliver { model in
                    model.filePicker.presentKeePassFilePicker(
                        for: request,
                        resolve: { [weak self] id, uri, name, size, token in
                            guard let self, !self.lifetime.isCancelled else { return }
                            self.source.resolveKeePassFilePicker(
                                requestId: id, uri: uri, name: name, size: size, accessToken: token)
                        },
                        cancel: { [weak self] id in
                            guard let self, !self.lifetime.isCancelled else { return }
                            self.source.cancelKeePassFilePicker(requestId: id)
                        }
                    )
                }
            }
            return source.subscribe(
                onChange: { snapshot in deliver { $0.keepass = snapshot } },
                onClose: { deliver { $0.complete() } },
                onWebDavChange: { snapshot in
                    deliver { model in
                        if let snapshot, snapshot.id == model.dismissedWebDavId { return }
                        model.keepassWebDav = snapshot
                    }
                }
            )
        }
    }

    /// Ending the subscription drops every callback still queued behind the completion.
    private func complete() {
        filePicker.cancel()
        stopObservation(\.keepassLoginSubscription)
        lifetime.cancel()
        keepassWebDav = nil
        keepassDidSucceed = true
    }

    func stopKeePassLoginObservation() {
        filePicker.cancel()
        lifetime.cancel()
        stopObservation(\.keepassLoginSubscription)
        keepass = KeePassLoginSnapshot.companion.empty
        keepassDidSucceed = false
        keepassWebDav = nil
    }

    /// Selecting a tab makes the shared producer launch the matching file picker.
    func selectKeePassTab(key: String) {
        source.selectKeePassTab(key: key)
    }

    /// Selecting WebDAV surfaces the settings sheet through `keepassWebDav`.
    func selectKeePassLocation(key: String) {
        source.selectKeePassLocation(key: key)
    }

    /// Re-picks the database file (or re-opens the WebDAV form for a WebDAV location).
    func pickKeePassDbFile() {
        source.pickKeePassDbFile()
    }

    func clearKeePassDbFile() {
        source.clearKeePassDbFile()
    }

    func pickKeePassKeyFile() {
        source.pickKeePassKeyFile()
    }

    func clearKeePassKeyFile() {
        source.clearKeePassKeyFile()
    }

    func setKeePassPassword(text: String) {
        source.setKeePassPassword(text: text)
    }

    func submitKeePassLogin() {
        source.submitKeePassLogin()
    }

    /// `id` is "url", "username" or "password".
    func setWebDavField(sessionId: String, id: String, text: String) {
        source.setWebDavField(sessionId: sessionId, id: id, text: text)
    }

    /// Validates and saves the WebDAV settings; the producer then delivers the
    /// location to the KeePass form and the sheet dismisses via `keepassWebDav`.
    func submitWebDavSettings(sessionId: String) {
        source.submitWebDavSettings(sessionId: sessionId)
    }

    /// Validates the settings and pings the server; the result arrives as a toast.
    func testWebDavConnection(sessionId: String) {
        source.testWebDavConnection(sessionId: sessionId)
    }

    func dismissWebDavSettings() {
        dismissedWebDavId = keepassWebDav?.id
        keepassWebDav = nil
        source.cancelWebDavSettings()
    }
}
