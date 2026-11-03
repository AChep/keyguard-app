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
        remoteSettings = RemoteSettingsModel(source: source.remoteSettings)
        lifetime = BridgeObservation(cancel: { source.close() })
    }

    private(set) var keepass: KeePassLoginSnapshot = KeePassLoginSnapshot.companion.empty

    private(set) var keepassDidSucceed = false

    /// The settings sheet of a WebDAV or S3 database.
    let remoteSettings: RemoteSettingsModel

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
                onWebDavChange: { snapshot in deliver { $0.remoteSettings.receiveWebDav(snapshot) } },
                onS3Change: { snapshot in deliver { $0.remoteSettings.receiveS3(snapshot) } }
            )
        }
    }

    /// Ending the subscription drops every callback still queued behind the completion.
    private func complete() {
        filePicker.cancel()
        stopObservation(\.keepassLoginSubscription)
        lifetime.cancel()
        remoteSettings.clear()
        keepassDidSucceed = true
    }

    func stopKeePassLoginObservation() {
        filePicker.cancel()
        lifetime.cancel()
        stopObservation(\.keepassLoginSubscription)
        keepass = KeePassLoginSnapshot.companion.empty
        keepassDidSucceed = false
        remoteSettings.clear()
    }

    /// Selecting a tab makes the shared producer launch the matching file picker.
    func selectKeePassTab(key: String) {
        source.selectKeePassTab(key: key)
    }

    /// Selecting WebDAV or S3 surfaces the settings sheet through `remoteSettings`.
    func selectKeePassLocation(key: String) {
        source.selectKeePassLocation(key: key)
    }

    /// Re-picks the database file (or re-opens the settings form for a remote location).
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
}
