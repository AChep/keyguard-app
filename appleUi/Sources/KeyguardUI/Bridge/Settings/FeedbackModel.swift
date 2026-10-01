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

    /// Only live while the "Contact us" sheet is presented.
    private(set) var feedback: FeedbackSnapshot = FeedbackSnapshot.companion.empty

    @ObservationIgnored private var feedbackSubscription: BridgeObservation?

    /// Idempotent; balance with `stopFeedbackObservation()`.
    func startFeedbackObservation() {
        startObservation(\.feedbackSubscription, into: \.feedback, observe: core.observeFeedback)
    }

    func stopFeedbackObservation() {
        stopObservation(\.feedbackSubscription, resetting: \.feedback, to: FeedbackSnapshot.companion.empty)
    }

    func setFeedbackMessage(_ text: String) { core.setFeedbackMessage(text: text) }

    func submitFeedback() { core.submitFeedback() }
}
