import Foundation
import Observation
import KeyguardShared

/// One pairing screen of linked apps. Each appearance runs a new pairing; leaving the
/// screen ends it, as a code works once.
@MainActor
@Observable
final class KeyguardPairingModel: SnapshotObserving {
    private let makeSource: () -> any KeyguardPairingSource
    @ObservationIgnored private var source: (any KeyguardPairingSource)?
    @ObservationIgnored private var subscription: BridgeObservation?

    init(makeSource: @escaping () -> any KeyguardPairingSource) {
        self.makeSource = makeSource
        snapshot = KeyguardPairingSnapshot.companion.empty
    }

    private(set) var snapshot: KeyguardPairingSnapshot

    /// Set once the vault locks; the screen then closes.
    private(set) var didComplete = false

    /// Call when the screen appears; balance with `stop()`.
    func start() {
        guard source == nil else { return }
        let source = makeSource()
        self.source = source
        startObservation(\.subscription) { deliver in
            source.subscribe(
                onChange: { snapshot in deliver { $0.snapshot = snapshot } },
                onClose: { deliver { $0.complete() } }
            )
        }
    }

    /// Ending the subscription drops every callback still queued behind the completion.
    private func complete() {
        stopObservation(\.subscription)
        didComplete = true
    }

    func stop() {
        stopObservation(\.subscription)
        source?.close()
        source = nil
        snapshot = KeyguardPairingSnapshot.companion.empty
        didComplete = false
    }

    func setCode(text: String) {
        source?.setCode(text: text)
    }

    func submitCode() {
        source?.submitCode()
    }

    /// A code from the camera or an image; one that is not a pairing code shows an error.
    func submitScannedCode(text: String) {
        source?.submitScannedCode(text: text)
    }

    func newCode() {
        source?.newCode()
    }

    func copyCode() {
        source?.copyCode()
    }

    /// The digits the other device shows; six of them are checked at once.
    func setDigits(text: String) {
        source?.setDigits(text: text)
    }

    func submitDigits() {
        source?.submitDigits()
    }

    func approve() {
        source?.approve()
    }

    func reject() {
        source?.reject()
    }

    func retry() {
        source?.retry()
    }

    func addAnother() {
        source?.addAnother()
    }
}
