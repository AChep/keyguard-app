import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class DetailObservationIsolationTests: XCTestCase {
    @MainActor
    func testAccountDetailRejectsReplacedAndStoppedSnapshots() async throws {
        let probe = DetailObservationProbe<AccountDetailSnapshot>()
        let model = AccountsModel(coreProvider: unusedCore, observeDetail: { _, callback in probe.subscribe(callback) })
        try await checkReplacement(
            probe, first: account("A"), second: account("B"), empty: AccountDetailSnapshot.companion.empty,
            start: { model.startAccountDetailObservation(accountId: String($0)) },
            stop: model.stopAccountDetailObservation,
            snapshot: { model.accountDetail }
        )
    }

    @MainActor
    func testSendDetailRejectsReplacedAndStoppedSnapshots() async throws {
        let probe = DetailObservationProbe<SendDetailSnapshot>()
        let model = SendModel(coreProvider: unusedCore, observeDetail: { _, _, callback in probe.subscribe(callback) })
        try await checkReplacement(
            probe, first: send("A"), second: send("B"), empty: SendDetailSnapshot.companion.empty,
            start: { model.startSendDetailObservation(itemId: String($0), accountId: "account") },
            stop: model.stopSendDetailObservation,
            snapshot: { model.sendDetail }
        )
    }

    @MainActor
    func testDisappearingSendDetailCannotStopItsReplacement() async throws {
        let probe = DetailObservationProbe<SendDetailSnapshot>()
        let model = SendModel(coreProvider: unusedCore, observeDetail: { _, _, callback in probe.subscribe(callback) })
        let oldOwner = model.startSendDetailObservation(itemId: "A", accountId: "account")
        let newOwner = model.startSendDetailObservation(itemId: "B", accountId: "account")
        model.stopSendDetailObservation(owner: oldOwner)
        XCTAssertEqual(probe.cancellations, 1)
        probe.callbacks[1](send("B"))
        try await settle()
        XCTAssertEqual(model.sendDetail.title, "B")
        model.stopSendDetailObservation(owner: newOwner)
        XCTAssertEqual(probe.cancellations, 2)
        XCTAssertNil(model.sendDetailIdentity)
    }

    @MainActor
    func testPasswordHistoryRejectsReplacedAndStoppedSnapshots() async throws {
        let probe = DetailObservationProbe<PasswordHistorySnapshot>()
        let model = CipherDetailModel(
            coreProvider: unusedCore, observePasswordHistory: { _, callback in probe.subscribe(callback) })
        let makeSnapshot = { (count: Int32) in
            PasswordHistorySnapshot(
                loaded: true, notFound: false, items: [], selectionCount: count, selectionActions: [], actions: [])
        }
        try await checkReplacement(
            probe, first: makeSnapshot(1), second: makeSnapshot(2), empty: PasswordHistorySnapshot.companion.empty,
            start: { model.startPasswordHistoryObservation(itemId: String($0)) },
            stop: model.stopPasswordHistoryObservation,
            snapshot: { model.passwordHistory }
        )
    }

    @MainActor
    func testAutofillGeneratorRejectsReplacedAndStoppedSnapshots() async throws {
        let probe = DetailObservationProbe<GeneratorSnapshot>()
        let model = AutofillGeneratorModel(
            coreProvider: unusedCore, observeGenerator: { _, _, _, callback in probe.subscribe(callback) })
        let makeSnapshot = { (title: String) in
            GeneratorSnapshot(
                loaded: true, typeTitle: title, types: [], value: nil, suggestions: [], tip: nil,
                length: nil, filters: [], options: [], canOpenHistory: false
            )
        }
        try await checkReplacement(
            probe, first: makeSnapshot("Username"), second: makeSnapshot("Password"),
            empty: GeneratorSnapshot.companion.empty,
            start: { model.startAutofillGeneratorObservation(username: $0 == 0, password: $0 != 0, uris: []) },
            stop: model.stopAutofillGeneratorObservation,
            snapshot: { model.autofillGenerator }
        )
    }

    @MainActor
    func testAccountIdentityTravelsWithAcceptedSnapshotAndSurvivesSameAccountUpdates() async throws {
        let probe = DetailObservationProbe<AccountDetailSnapshot>()
        let model = AccountsModel(coreProvider: unusedCore, observeDetail: { _, callback in probe.subscribe(callback) })
        model.startAccountDetailObservation(accountId: "A")
        probe.callbacks[0](account("Initial"))
        try await settle()
        let identity = model.accountDetailIdentity
        XCTAssertEqual(identity, "A")
        probe.callbacks[0](account("Updated"))
        try await settle()
        XCTAssertEqual(model.accountDetailIdentity, identity)
        XCTAssertEqual(model.accountDetail.title, "Updated")

        model.startAccountDetailObservation(accountId: "B")
        XCTAssertNil(model.accountDetailIdentity)
        probe.callbacks[0](account("Late A"))
        try await settle()
        XCTAssertNil(model.accountDetailIdentity)
        probe.callbacks[1](account("B"))
        try await settle()
        XCTAssertEqual(model.accountDetailIdentity, "B")
        XCTAssertEqual(model.accountDetail.title, "B")
        model.stopAccountDetailObservation()
        XCTAssertNil(model.accountDetailIdentity)
    }

    @MainActor
    func testSendIdentityIncludesAccountAndTravelsWithAcceptedSnapshot() async throws {
        let probe = DetailObservationProbe<SendDetailSnapshot>()
        let model = SendModel(coreProvider: unusedCore, observeDetail: { _, _, callback in probe.subscribe(callback) })
        model.startSendDetailObservation(itemId: "send", accountId: "A")
        probe.callbacks[0](send("Initial"))
        try await settle()
        let identity = model.sendDetailIdentity
        XCTAssertEqual(identity, SendDetailIdentity(itemId: "send", accountId: "A"))
        probe.callbacks[0](send("Updated"))
        try await settle()
        XCTAssertEqual(model.sendDetailIdentity, identity)

        model.startSendDetailObservation(itemId: "send", accountId: "B")
        XCTAssertNil(model.sendDetailIdentity)
        probe.callbacks[0](send("Late A"))
        try await settle()
        XCTAssertNil(model.sendDetailIdentity)
        probe.callbacks[1](send("B"))
        try await settle()
        XCTAssertEqual(model.sendDetailIdentity, SendDetailIdentity(itemId: "send", accountId: "B"))
        XCTAssertNotEqual(model.sendDetailIdentity, identity)
        XCTAssertEqual(model.sendDetail.title, "B")
        model.stopSendDetailObservation()
        XCTAssertNil(model.sendDetailIdentity)
    }

    private func unusedCore() -> KeyguardCore {
        fatalError("Detail observation tests must not access the user's vault")
    }

    private func account(_ title: String) -> AccountDetailSnapshot {
        AccountDetailSnapshot(
            loaded: true, notFound: false, title: title, host: nil, email: nil, accountType: nil,
            openWebVaultActionId: nil, openLocalVaultActionId: nil, items: [], actions: []
        )
    }

    private func send(_ title: String) -> SendDetailSnapshot {
        SendDetailSnapshot(
            title: title, typeIcon: "Text", canCopy: false, canShare: false, canEdit: false,
            isLoading: false, notFound: false, actions: [], items: []
        )
    }

    @MainActor
    private func checkReplacement<Snapshot: Equatable>(
        _ probe: DetailObservationProbe<Snapshot>, first: Snapshot, second: Snapshot, empty: Snapshot,
        start: (Int) -> Void, stop: () -> Void, snapshot: () -> Snapshot
    ) async throws {
        start(0)
        probe.callbacks[0](first)
        // Replace the producer before the already-enqueued MainActor callback runs.
        start(1)
        XCTAssertEqual(probe.cancellations, 1)
        try await settle()
        XCTAssertEqual(snapshot(), empty)
        probe.callbacks[1](second)
        try await settle()
        XCTAssertEqual(snapshot(), second)
        probe.callbacks[0](first)
        try await settle()
        XCTAssertEqual(snapshot(), second)
        probe.callbacks[1](first)
        stop()
        try await settle()
        XCTAssertEqual(snapshot(), empty)
        XCTAssertEqual(probe.cancellations, 2)
    }

    @MainActor
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(20))
    }
}

private final class DetailObservationProbe<Snapshot> {
    var callbacks: [(Snapshot) -> Void] = []
    var cancellations = 0

    func subscribe(_ callback: @escaping (Snapshot) -> Void) -> BridgeObservation {
        callbacks.append(callback)
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }
}
