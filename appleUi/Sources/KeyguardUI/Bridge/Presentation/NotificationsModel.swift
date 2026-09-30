import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class NotificationsModel {
    private let observeMessages: (@escaping (MessageSnapshot) -> Void) -> BridgeObservation
    private let scheduleDismiss: (TimeInterval, @escaping @MainActor () -> Void) -> BridgeObservation

    convenience init(core: KeyguardCore) {
        self.init(
            observeMessages: { BridgeObservation(core.observeMessages(onMessage: $0)) },
            scheduleDismiss: Self.scheduleDismiss
        )
    }

    init(
        observeMessages: @escaping (@escaping (MessageSnapshot) -> Void) -> BridgeObservation,
        scheduleDismiss: @escaping (TimeInterval, @escaping @MainActor () -> Void) -> BridgeObservation
    ) {
        self.observeMessages = observeMessages
        self.scheduleDismiss = scheduleDismiss
    }

    @ObservationIgnored private var started = false

    func start() {
        guard !started else { return }
        started = true
        // Surface producer runtime errors (e.g. a wrong master password on the
        // unlock screen) that the shared screen executors route to the global
        // message bus instead of into a screen state.
        messagesSubscription = observeMessages { [weak self] snapshot in
            Task { @MainActor [weak self] in
                self?.applyMessage(message: snapshot)
            }
        }
    }

    /// Transient messages shown as auto-dismissing toasts.

    private(set) var toasts: [ToastItem] = []

    @ObservationIgnored private var toastDismissWork: [String: BridgeObservation] = [:]

    @ObservationIgnored private var messagesSubscription: BridgeObservation?

    private func applyMessage(message snapshot: MessageSnapshot) {
        showToast(snapshot)
    }

    private func showToast(_ snapshot: MessageSnapshot) {
        let item = ToastItem(
            id: snapshot.id,
            type: snapshot.type,
            title: snapshot.title,
            text: snapshot.text
        )
        if let index = toasts.firstIndex(where: { $0.id == item.id }) {
            toasts[index] = item
        } else {
            toasts.append(item)
        }
        // (Re)arm auto-dismiss using the producer-supplied duration.
        toastDismissWork[item.id]?.cancel()
        let generation = UUID()
        let seconds = Double(snapshot.durationMillis) / 1000.0
        let scheduled = scheduleDismiss(seconds) { [weak self] in
            guard self?.toastDismissWork[item.id]?.id == generation else { return }
            self?.dismissToast(item.id)
        }
        toastDismissWork[item.id] = BridgeObservation(id: generation, cancel: { scheduled.cancel() })
    }

    private static func scheduleDismiss(
        after seconds: TimeInterval,
        action: @escaping @MainActor () -> Void
    ) -> BridgeObservation {
        let task = Task { @MainActor in
            do { try await Task.sleep(for: .seconds(seconds)) } catch { return }
            action()
        }
        return BridgeObservation(cancel: { task.cancel() })
    }

    func dismissToast(_ id: String) {
        toasts.removeAll { $0.id == id }
        toastDismissWork[id]?.cancel()
        toastDismissWork[id] = nil
    }

    /// Like `run`, but does not toggle the global busy overlay — for fire-and-forget
    /// sub-screen mutations (duplicate / delete) that surface errors only.
    func perform(_ operation: @escaping () async throws -> Void) {
        Task { @MainActor in
            do {
                try await operation()
            } catch {
                showOperationError(error)
            }
        }
    }

    func showOperationError(_ error: Error) {
        // These direct bridge operations do not run through the shared message
        // hub. Use the same visible feedback as producer errors on every surface.
        showError(title: L10n.prefItemAutomaticBackupsPanelErrorLabel, text: error.localizedDescription)
    }

    func showLinkOpeningError() {
        showError(title: L10n.errorFailedOpenLink, text: nil)
    }

    private func showError(title: String, text: String?) {
        showToast(
            MessageSnapshot(
                id: UUID().uuidString,
                type: "ERROR",
                title: title,
                text: text,
                durationMillis: 6000
            ))
    }
}
