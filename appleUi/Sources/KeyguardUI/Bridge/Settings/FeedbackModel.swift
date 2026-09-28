import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class FeedbackModel {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    /// "Contact us" (feedback) sheet state, produced by the shared Kotlin feedback
    /// producer running headless inside `KeyguardCore`. Only live while the feedback
    /// modal sheet is presented (the macOS / iOS Settings → About row).
    private(set) var feedback: FeedbackSnapshot = FeedbackSnapshot.companion.empty

    @ObservationIgnored private var feedbackSubscription: BridgeObservation?

    /// Starts the standalone "Contact us" feedback observation backing the modal
    /// sheet (Settings → About). Idempotent; pair with `stopFeedbackObservation`.
    func startFeedbackObservation() {
        guard feedbackSubscription == nil else { return }
        feedbackSubscription = BridgeObservation(
            core.observeFeedback(onChange: { [weak self] snapshot in
                Task { @MainActor [weak self] in
                    self?.feedback = snapshot
                }
            }))
    }

    func stopFeedbackObservation() {
        feedbackSubscription?.cancel()
        feedbackSubscription = nil
        feedback = FeedbackSnapshot.companion.empty
    }

    func setFeedbackMessage(_ text: String) { core.setFeedbackMessage(text: text) }

    func submitFeedback() { core.submitFeedback() }
}
