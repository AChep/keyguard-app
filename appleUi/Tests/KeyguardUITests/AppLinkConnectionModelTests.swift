import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class AppLinkConnectionModelTests: XCTestCase {
    @MainActor
    func testScreenKeepsOneSessionAndRoutesActions() async throws {
        var sources: [AppLinkConnectionSourceProbe] = []
        let model = AppLinkConnectionModel {
            let source = AppLinkConnectionSourceProbe()
            sources.append(source)
            return source
        }
        model.start()
        model.start()
        XCTAssertEqual(sources.count, 1)
        let source = try XCTUnwrap(sources.first)
        source.publish(snapshot(canSave: true))
        try await settle()
        XCTAssertTrue(model.snapshot.loaded)
        XCTAssertTrue(model.snapshot.canSave)
        XCTAssertEqual(model.network, KeyguardNetworkSnapshot.companion.empty)

        model.setNetworkField(id: "relay", text: "wss://relay.example.test")
        model.setAllowLan(value: false)
        model.setRelayStorage(value: true)
        model.save()
        XCTAssertEqual(
            source.actions,
            ["network:relay:wss://relay.example.test", "allowLan:false", "relayStorage:true", "save"]
        )

        model.stop()
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.cancellations, 1)
        XCTAssertFalse(model.snapshot.loaded)
    }

    @MainActor
    func testSavingCompletesTheScreen() async throws {
        let source = AppLinkConnectionSourceProbe()
        let model = AppLinkConnectionModel { source }
        model.start()
        source.publish(snapshot(canSave: true))
        try await settle()
        source.complete()
        try await settle()
        XCTAssertTrue(model.didComplete)
        // Nothing reaches a completed screen.
        source.publish(snapshot(canSave: false))
        try await settle()
        XCTAssertTrue(model.snapshot.canSave)
        model.stop()
        XCTAssertFalse(model.didComplete)
    }

    private func snapshot(canSave: Bool) -> AppLinkConnectionSnapshot {
        AppLinkConnectionSnapshot(
            loaded: true, network: KeyguardNetworkSnapshot.companion.empty, canSave: canSave, isSaving: false)
    }

    @MainActor
    private func settle() async throws { try await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
final class AppLinkConnectionSourceProbe: AppLinkConnectionSource {
    var publish: (AppLinkConnectionSnapshot) -> Void = { _ in }
    var complete: () -> Void = {}
    var closes = 0
    var cancellations = 0
    var actions: [String] = []

    func subscribe(
        onChange: @escaping (AppLinkConnectionSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation {
        publish = onChange
        complete = onClose
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }

    func close() { closes += 1 }
    func setNetworkField(id: String, text: String) { actions.append("network:\(id):\(text)") }
    func setAllowLan(value: Bool) { actions.append("allowLan:\(value)") }
    func setRelayStorage(value: Bool) { actions.append("relayStorage:\(value)") }
    func save() { actions.append("save") }
}
