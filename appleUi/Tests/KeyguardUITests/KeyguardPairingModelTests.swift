import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class KeyguardPairingModelTests: XCTestCase {
    @MainActor
    func testScreenKeepsOneSessionAndRoutesActions() async throws {
        var sources: [KeyguardPairingSourceProbe] = []
        let model = KeyguardPairingModel {
            let source = KeyguardPairingSourceProbe()
            sources.append(source)
            return source
        }
        model.start()
        // A second appearance of the same screen must not start another pairing.
        model.start()
        XCTAssertEqual(sources.count, 1)
        let source = try XCTUnwrap(sources.first)
        source.publish(snapshot(step: KeyguardPairingStepSnapshot.Connecting.shared))
        try await settle()
        XCTAssertEqual(source.starts, 1)
        XCTAssertTrue(model.snapshot.loaded)
        XCTAssertTrue(model.snapshot.step is KeyguardPairingStepSnapshot.Connecting)

        model.setCode(text: "KGP:CODE")
        model.submitCode()
        model.submitScannedCode(text: "KGP:SCANNED")
        model.newCode()
        model.copyCode()
        model.setDigits(text: "123456")
        model.submitDigits()
        model.approve()
        model.reject()
        model.retry()
        model.addAnother()
        XCTAssertEqual(
            source.actions,
            [
                "code:KGP:CODE", "submitCode", "scanned:KGP:SCANNED", "newCode", "copyCode", "digits:123456",
                "submitDigits", "approve", "reject", "retry", "addAnother",
            ])

        // Leaving the screen ends the pairing; coming back starts a new one.
        model.stop()
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.cancellations, 1)
        XCTAssertFalse(model.snapshot.loaded)
        model.start()
        XCTAssertEqual(sources.count, 2)
        model.stop()
    }

    @MainActor
    func testCompletionRejectsLateSnapshotsUntilTheScreenLeaves() async throws {
        let source = KeyguardPairingSourceProbe()
        var created = 0
        let model = KeyguardPairingModel {
            created += 1
            return source
        }
        model.start()
        source.complete()
        try await settle()
        source.publish(snapshot(step: KeyguardPairingStepSnapshot.Transferring.shared))
        try await settle()
        XCTAssertTrue(model.didComplete)
        XCTAssertFalse(model.snapshot.loaded)
        // A completed pairing is not observed again while its screen stays.
        model.start()
        XCTAssertEqual(created, 1)
        model.stop()
        XCTAssertFalse(model.didComplete)
        XCTAssertEqual(source.closes, 1)
    }

    @MainActor
    func testPairingAsComesFromTheSnapshot() async throws {
        let source = KeyguardPairingSourceProbe()
        let model = KeyguardPairingModel { source }
        model.start()
        source.publish(snapshot(step: nil, pairingAsText: "Pairing as Mac via Keyguard relay"))
        try await settle()
        XCTAssertEqual(model.snapshot.pairingAsText, "Pairing as Mac via Keyguard relay")
        XCTAssertEqual(model.snapshot.stage, .idle)
        model.stop()
    }

    private func snapshot(
        step: KeyguardPairingStepSnapshot?,
        pairingAsText: String = ""
    ) -> KeyguardPairingSnapshot {
        KeyguardPairingSnapshot(
            loaded: true, step: step, stage: .idle, pairingAsText: pairingAsText,
            premiumRequired: false, canAddAnother: false)
    }

    @MainActor
    private func settle() async throws { try await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
final class KeyguardPairingSourceProbe: KeyguardPairingSource {
    var publish: (KeyguardPairingSnapshot) -> Void = { _ in }
    var complete: () -> Void = {}
    var starts = 0
    var closes = 0
    var cancellations = 0
    var actions: [String] = []

    func subscribe(
        onChange: @escaping (KeyguardPairingSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation {
        starts += 1
        publish = onChange
        complete = onClose
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }

    func close() { closes += 1 }
    func setCode(text: String) { actions.append("code:\(text)") }
    func submitCode() { actions.append("submitCode") }
    func submitScannedCode(text: String) { actions.append("scanned:\(text)") }
    func newCode() { actions.append("newCode") }
    func copyCode() { actions.append("copyCode") }
    func setDigits(text: String) { actions.append("digits:\(text)") }
    func submitDigits() { actions.append("submitDigits") }
    func approve() { actions.append("approve") }
    func reject() { actions.append("reject") }
    func retry() { actions.append("retry") }
    func addAnother() { actions.append("addAnother") }
}
