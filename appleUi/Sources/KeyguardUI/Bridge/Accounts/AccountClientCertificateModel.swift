import Foundation
import Observation
import KeyguardShared

@MainActor
protocol AccountClientCertificateSource: AnyObject {
    func subscribe(
        onChange: @escaping (AccountClientCertificateSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation
    func setFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Void)?)
    func resolveFilePicker(requestId: String, uri: String, name: String?, size: Int64, accessToken: String?)
    func cancelFilePicker(requestId: String)
    func importCertificate()
    func dropFile(uri: String, name: String?, size: Int64)
    func removeCertificate()
    func save()
    func confirmRemoval()
    func dismissRemoval()
    func invokeAction(id: String)
    func close()
}

extension AccountClientCertificateSession: AccountClientCertificateSource {
    func subscribe(
        onChange: @escaping (AccountClientCertificateSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation {
        BridgeObservation(observe(onChange: onChange, onClose: onClose))
    }
}

@MainActor
@Observable
final class AccountClientCertificateModel {
    private let session: any AccountClientCertificateSource
    let dialogs: DialogsModel?
    let filePicker = FilePickerSession()
    private(set) var snapshot: AccountClientCertificateSnapshot?
    private(set) var finished = false
    @ObservationIgnored private let lifetime: BridgeObservation
    @ObservationIgnored private var observation: BridgeObservation?
    @ObservationIgnored private var closed = false

    init(session: any AccountClientCertificateSource, dialogs: DialogsModel? = nil) {
        self.session = session
        self.dialogs = dialogs
        lifetime = BridgeObservation(cancel: { session.close() })
    }

    func start() {
        guard observation == nil, !closed else { return }
        dialogs?.startFormDialogs()
        session.setFilePickerRequestHandler { [weak self] request in
            guard let self, !self.closed else { return }
            self.filePicker.presentFilePicker(
                for: request, persistent: true, resolveAfterDismissal: true,
                resolve: { [weak self] id, uri, name, size, token in
                    guard let self, !self.closed else { return }
                    self.session.resolveFilePicker(requestId: id, uri: uri, name: name, size: size, accessToken: token)
                },
                cancel: { [weak self] id in self?.session.cancelFilePicker(requestId: id) }
            )
        }
        let subscription = session.subscribe(
            onChange: { [weak self] snapshot in
                guard let self, !self.closed else { return }
                self.snapshot = snapshot
            },
            onClose: { [weak self] in
                guard let self, !self.closed else { return }
                self.finished = true
                self.close()
            }
        )
        if closed { subscription.cancel() } else { observation = subscription }
    }

    func importCertificate() { if !closed { session.importCertificate() } }
    func removeCertificate() { if !closed { session.removeCertificate() } }
    func save() { if !closed { session.save() } }
    func confirmRemoval() { if !closed { session.confirmRemoval() } }
    func dismissRemoval() { if !closed { session.dismissRemoval() } }
    func invokeAction(_ id: String) { if !closed { session.invokeAction(id: id) } }

    /// Imports a `.p12` or `.pfx` file dropped onto the screen.
    func dropFile(url: URL) {
        guard !closed else { return }
        let file = url.fileNameAndSize
        session.dropFile(uri: url.absoluteString, name: file.name, size: file.size)
    }

    func close() {
        guard !closed else { return }
        closed = true
        filePicker.cancel()
        observation?.cancel()
        observation = nil
        lifetime.cancel()
        dialogs?.stopFormDialogs()
        snapshot = nil
    }
}
