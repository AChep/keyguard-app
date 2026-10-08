import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class CipherDetailSessionModelTests: XCTestCase {
    @MainActor
    func testWindowsKeepTheirDataActionsAndLifetimeIndependent() async throws {
        let firstSource = DetailSessionProbe()
        let secondSource = DetailSessionProbe()
        let first = CipherDetailModel.cipherDetail { _ in firstSource }
        let second = CipherDetailModel.cipherDetail { _ in secondSource }
        first.setTarget(target("A"))
        second.setTarget(target("B"))
        let firstActions = first.actions(owner: try XCTUnwrap(first.identity))
        let secondActions = second.actions(owner: try XCTUnwrap(second.identity))
        firstSource.onChange?(snapshot("A"))
        secondSource.onChange?(snapshot("B"))
        firstSource.onTotpChange?(totp("A"))
        secondSource.onTotpChange?(totp("B"))
        try await settle()

        XCTAssertEqual(first.detail?.detail.cipherId, "A")
        XCTAssertEqual(second.detail?.detail.cipherId, "B")
        XCTAssertEqual(first.detail?.totp.value?.cipherId, "A")
        XCTAssertEqual(second.detail?.totp.value?.cipherId, "B")
        firstActions { $0.invokeAction(id: "copy") }
        secondActions { $0.toggleFavorite() }
        XCTAssertEqual(firstSource.actions, ["copy"])
        XCTAssertEqual(secondSource.favorites, 1)

        first.stop()
        firstActions { $0.invokeAction(id: "late") }
        secondActions { $0.invokeAction(id: "copy") }
        XCTAssertNil(first.detail)
        XCTAssertEqual(firstSource.cancellations, 1)
        XCTAssertEqual(firstSource.actions, ["copy"])
        XCTAssertEqual(secondSource.cancellations, 0)
        XCTAssertEqual(secondSource.actions, ["copy"])
        second.stop()
    }

    @MainActor
    func testReplacementRejectsQueuedFramesAndOldFormActions() async throws {
        var sources: [DetailSessionProbe] = []
        var targets: [ItemDetailTarget] = []
        let model = CipherDetailModel.cipherDetail { target in
            targets.append(target)
            let source = DetailSessionProbe()
            sources.append(source)
            return source
        }
        model.setTarget(target("same", account: "A"))
        let oldActions = model.actions(owner: try XCTUnwrap(model.identity))
        sources[0].onChange?(snapshot("same"))
        sources[0].onTotpChange?(totp("same"))
        model.setTarget(target("same", account: "B"))
        model.setTarget(target("same", account: "B"))
        XCTAssertEqual(sources.count, 2)
        XCTAssertEqual(targets.map(\.accountId), ["A", "B"])
        XCTAssertEqual(sources[0].cancellations, 1)
        try await settle()
        XCTAssertNil(model.detail)

        sources[1].onChange?(snapshot("same"))
        sources[1].onTotpChange?(totp("same"))
        try await settle()
        XCTAssertNotNil(model.detail?.totp.state(cipherId: "same", rowId: "otp"))
        oldActions { $0.invokeAction(id: "copy") }
        oldActions { $0.toggleFavorite() }
        XCTAssertTrue(sources[1].actions.isEmpty)
        XCTAssertEqual(sources[1].favorites, 0)

        sources[0].onChange?(snapshot("late"))
        sources[0].onTotpChange?(totp("late"))
        try await settle()
        XCTAssertEqual(model.detail?.detail.cipherId, "same")
        XCTAssertEqual(model.detail?.totp.value?.cipherId, "same")
        XCTAssertNil(model.detail?.totp.state(cipherId: "late", rowId: "otp"))
        model.setTarget(nil)
        sources[1].onChange?(snapshot("late"))
        sources[1].onTotpChange?(totp("late"))
        try await settle()
        XCTAssertNil(model.detail)
        XCTAssertEqual(sources[1].cancellations, 1)
    }

    @MainActor
    func testSameCipherPresentationsAreSeparateAndLockSnapshotsClearBothChannels() async throws {
        var sources: [DetailSessionProbe] = []
        let makeSource: (ItemDetailTarget) -> any CipherDetailSessionSource = { _ in
            let source = DetailSessionProbe()
            sources.append(source)
            return source
        }
        let first = CipherDetailModel.cipherDetail(makeSession: makeSource)
        let second = CipherDetailModel.cipherDetail(makeSession: makeSource)
        first.setTarget(target("same"))
        second.setTarget(target("same"))
        XCTAssertEqual(sources.count, 2)
        XCTAssertNotEqual(first.identity, second.identity)
        for source in sources {
            source.onChange?(snapshot("same"))
            source.onTotpChange?(totp("same"))
        }
        try await settle()
        for source in sources {
            source.onChange?(.companion.empty)
            source.onTotpChange?(nil)
        }
        try await settle()
        for model in [first, second] {
            XCTAssertEqual(model.detail?.detail, .companion.empty)
            XCTAssertNil(model.detail?.totp.value)
            model.stop()
        }
    }

    @MainActor
    func testReleasingThePresentationClosesItsSession() {
        let source = DetailSessionProbe()
        var model: CipherDetailModel? = CipherDetailModel.cipherDetail { _ in source }
        model?.setTarget(target("A"))
        weak var released = model
        model = nil
        XCTAssertNil(released)
        XCTAssertEqual(source.cancellations, 1)
    }

    private func target(_ itemId: String, account: String = "account") -> ItemDetailTarget {
        ItemDetailTarget(itemId: itemId, accountId: account)
    }

    private func snapshot(_ id: String) -> VaultDetailSnapshot {
        VaultDetailSnapshot(
            title: id, typeIcon: "Login", favorite: false, canToggleFavorite: true, isLoading: false, notFound: false,
            cipherId: id, items: [], iconUrl: nil, iconPlaceholder: nil, editActionId: nil, actions: [])
    }

    private func totp(_ id: String) -> VaultDetailTotpSnapshot {
        VaultDetailTotpSnapshot(cipherId: id, states: ["otp": TotpFieldSnapshot.companion.loading])
    }

    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(20))
    }
}

@MainActor
private final class DetailSessionProbe: CipherDetailSessionSource {
    var onChange: ((VaultDetailSnapshot) -> Void)?
    var onTotpChange: ((VaultDetailTotpSnapshot?) -> Void)?
    var cancellations = 0
    var actions: [String] = []
    var favorites = 0

    func subscribe(
        onChange: @escaping (VaultDetailSnapshot) -> Void,
        onTotpChange: @escaping (VaultDetailTotpSnapshot?) -> Void
    ) -> BridgeObservation {
        self.onChange = onChange
        self.onTotpChange = onTotpChange
        return BridgeObservation { [weak self] in self?.cancellations += 1 }
    }

    func invokeAction(id: String) { actions.append(id) }
    func toggleFavorite() { favorites += 1 }
}
