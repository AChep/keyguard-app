import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class AppLinkModelTests: XCTestCase {
    @MainActor
    func testScreenKeepsOneSessionAndRoutesActions() async throws {
        var sources: [AppLinkSourceProbe] = []
        let model = AppLinkModel {
            let source = AppLinkSourceProbe()
            sources.append(source)
            return source
        }
        model.start()
        model.start()
        XCTAssertEqual(sources.count, 1)
        let source = try XCTUnwrap(sources.first)
        source.publish(linked(["EMAIL_FORWARDERS"]))
        try await settle()
        XCTAssertEqual(source.starts, 1)
        XCTAssertTrue(model.snapshot.loaded)
        XCTAssertTrue(model.snapshot.linked)
        XCTAssertEqual(model.snapshot.categories.map(\.key), ["EMAIL_FORWARDERS"])

        XCTAssertEqual(model.snapshot.deviceLabel, "Mac")
        XCTAssertEqual(model.snapshot.relayText, "Keyguard relay")

        model.setCategory(key: "EMAIL_FORWARDERS", value: false)
        model.setCategory(key: "EMAIL_FORWARDERS", value: true)
        model.rename()
        XCTAssertEqual(
            source.actions, ["category:EMAIL_FORWARDERS:false", "category:EMAIL_FORWARDERS:true", "rename"])

        // Pushing a pairing screen may hide this one; returning observes again.
        model.stop()
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.cancellations, 1)
        XCTAssertFalse(model.snapshot.loaded)
        model.start()
        XCTAssertEqual(sources.count, 2)
        model.stop()
    }

    @MainActor
    func testStoppedScreenDropsLateSnapshots() async throws {
        let source = AppLinkSourceProbe()
        let model = AppLinkModel { source }
        model.start()
        model.stop()
        source.publish(linked(["late"]))
        try await settle()
        XCTAssertFalse(model.snapshot.loaded)
        XCTAssertTrue(model.snapshot.categories.isEmpty)
        // Actions of a stopped screen reach nothing.
        model.setCategory(key: "late", value: false)
        XCTAssertTrue(source.actions.isEmpty)
    }

    private func linked(_ keys: [String]) -> AppLinkSnapshot {
        let categories = keys.map { key in
            AppLinkSnapshot.Category(key: key, title: key, checked: true, enabled: true)
        }
        return AppLinkSnapshot(
            loaded: true, linked: true, premiumRequired: false, canLink: false,
            deviceLabel: "Mac", relayText: "Keyguard relay", lanText: nil, categories: categories)
    }

    @MainActor
    private func settle() async throws { try await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
final class AppLinkSourceProbe: AppLinkSource {
    var publish: (AppLinkSnapshot) -> Void = { _ in }
    var starts = 0
    var closes = 0
    var cancellations = 0
    var actions: [String] = []

    func subscribe(onChange: @escaping (AppLinkSnapshot) -> Void) -> BridgeObservation {
        starts += 1
        publish = onChange
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }

    func close() { closes += 1 }
    func setCategory(key: String, value: Bool) { actions.append("category:\(key):\(value)") }
    func rename() { actions.append("rename") }
}
