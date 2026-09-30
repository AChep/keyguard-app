import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class FeedbackModel: SnapshotObserving {
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
        startObservation(\.feedbackSubscription, into: \.feedback, observe: core.observeFeedback)
    }

    func stopFeedbackObservation() {
        stopObservation(\.feedbackSubscription, resetting: \.feedback, to: FeedbackSnapshot.companion.empty)
    }

    func setFeedbackMessage(_ text: String) { core.setFeedbackMessage(text: text) }

    func submitFeedback() { core.submitFeedback() }
}
