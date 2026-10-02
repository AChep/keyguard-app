import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class WorkspaceSessionModelTests: XCTestCase {
    @MainActor
    func testGeneratorOwnersRejectOldToolbarActionsAndQueuedSnapshots() async throws {
        try await exercise(GeneratorSnapshot.companion.empty)
    }

    @MainActor
    func testWatchtowerOwnersRejectOldFilterActionsAndQueuedSnapshots() async throws {
        try await exercise(WatchtowerSnapshot.companion.empty)
    }

    @MainActor
    func testSendListOwnersKeepQueriesAndSelectionSeparateAcrossReopening() async throws {
        func snapshot(_ query: String, _ selectionCount: Int32) -> SendListSnapshot {
            SendListSnapshot(
                loaded: true, needsAccount: false, query: query, queryRevision: 0, itemsRevision: 0,
                filters: [], sort: [], items: [], canClearFilters: false, canClearSort: false,
                activeFilterCount: 0, totalCount: 2, selectionCount: selectionCount, selectionActions: [],
                listActions: [], canDropFile: true, canCreateFileSend: true)
        }
        try await exercise(snapshot("first", 1), secondSnapshot: snapshot("second", 0))
    }

    @MainActor
    func testPasswordFormsKeepDraftsSeparateAndRejectClosedOwnerCallbacks() async throws {
        func snapshot(_ password: String) -> MasterPasswordSnapshot {
            MasterPasswordSnapshot(
                loaded: true, password: password, passwordError: nil, isLoading: false, canSubmit: true,
                hasBiometric: true, biometricEnabled: false, crashlyticsEnabled: false)
        }
        try await exercise(snapshot("first draft"), secondSnapshot: snapshot("second draft"))
    }

    @MainActor
    private func exercise<Snapshot: AnyObject>(_ snapshot: Snapshot, secondSnapshot: Snapshot? = nil) async throws {
        let secondSnapshot = secondSnapshot ?? snapshot
        var sources: [WorkspaceSource<Snapshot>] = []
        let make: (Bool) -> WorkspaceSource<Snapshot> = { _ in
            let source = WorkspaceSource<Snapshot>()
            sources.append(source)
            return source
        }
        let first = DetailSessionModel(makeSession: make, subscribe: { $0.subscribe($1) })
        let second = DetailSessionModel(makeSession: make, subscribe: { $0.subscribe($1) })
        first.setTarget(true)
        second.setTarget(true)
        let oldOwner = try XCTUnwrap(first.identity)
        let secondOwner = try XCTUnwrap(second.identity)
        sources[0].publish?(snapshot)
        sources[1].publish?(secondSnapshot)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(first.detail === snapshot)
        XCTAssertTrue(second.detail === secondSnapshot)
        first.stop()
        first.setTarget(true)
        sources[0].publish?(snapshot)
        let staleQueryOrDrop = { first.perform(owner: oldOwner) { $0.actions += 1 } }
        staleQueryOrDrop()
        second.perform(owner: secondOwner) { $0.actions += 1 }
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertNil(first.detail)
        XCTAssertNotEqual(first.identity, oldOwner)
        XCTAssertTrue(second.detail === secondSnapshot)
        XCTAssertEqual(sources.map(\.actions), [0, 1, 0])
        XCTAssertEqual(sources.map(\.cancellations), [1, 0, 0])
        let reopenedOwner = try XCTUnwrap(first.identity)
        sources[2].publish?(snapshot)
        first.perform(owner: reopenedOwner) { $0.actions += 1 }
        staleQueryOrDrop()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(first.detail === snapshot)
        XCTAssertEqual(sources.map(\.actions), [0, 1, 1])
        first.stop()
        second.stop()
    }
}

@MainActor
private final class WorkspaceSource<Snapshot> {
    var publish: ((Snapshot) -> Void)?
    var actions = 0
    var cancellations = 0

    func subscribe(_ callback: @escaping (Snapshot) -> Void) -> BridgeObservation {
        publish = callback
        return BridgeObservation { [weak self] in self?.cancellations += 1 }
    }
}
