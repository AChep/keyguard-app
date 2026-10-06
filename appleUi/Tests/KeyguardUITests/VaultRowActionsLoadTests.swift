import XCTest
@testable import KeyguardUI

final class VaultRowActionsLoadTests: XCTestCase {
    private let actions = [VaultAction(id: "favorite", title: "Favorite")]

    func testChangedRowRevisionHidesOldActionsBeforeReloadStarts() {
        let original = VaultRowContextMenuRequest(rowId: "item", revision: 1)
        let changed = VaultRowContextMenuRequest(rowId: "item", revision: 2)
        let pending = VaultRowActionsLoad(request: original, generation: 1)
        let loaded = pending.resolving(actions, for: pending)

        XCTAssertEqual(loaded.actions(for: original), actions)
        XCTAssertTrue(loaded.actions(for: changed).isEmpty)
    }

    func testRecycledCellDoesNotExposePreviousRowsActions() {
        let original = VaultRowContextMenuRequest(rowId: "first", revision: 1)
        let recycled = VaultRowContextMenuRequest(rowId: "second", revision: 1)
        let pending = VaultRowActionsLoad(request: original, generation: 1)
        let loaded = pending.resolving(actions, for: pending)

        XCTAssertTrue(loaded.actions(for: recycled).isEmpty)
    }

    func testOutOfOrderCompletionCannotOverwriteNewRowVersion() {
        let original = VaultRowContextMenuRequest(rowId: "item", revision: 1)
        let changed = VaultRowContextMenuRequest(rowId: "item", revision: 2)
        let oldLoad = VaultRowActionsLoad(request: original, generation: 1)
        let newLoad = VaultRowActionsLoad(request: changed, generation: 2)
        let updatedActions = [VaultAction(id: "unfavorite", title: "Unfavorite")]
        let loaded = newLoad.resolving(updatedActions, for: newLoad)
        let afterLateCompletion = loaded.resolving(actions, for: oldLoad)

        XCTAssertEqual(afterLateCompletion.actions(for: changed), updatedActions)
        XCTAssertTrue(afterLateCompletion.actions(for: original).isEmpty)
    }

    func testSameRowRetryRejectsPreviousTasksCompletion() {
        let request = VaultRowContextMenuRequest(rowId: "item", revision: 1)
        let oldLoad = VaultRowActionsLoad(request: request, generation: 1)
        let retry = VaultRowActionsLoad(request: request, generation: 2)
        let afterLateCompletion = retry.resolving(actions, for: oldLoad)

        XCTAssertTrue(afterLateCompletion.actions(for: request).isEmpty)
        XCTAssertEqual(afterLateCompletion.resolving(actions, for: retry).actions(for: request), actions)
    }

    func testPlaceholderActionsAreNotReusedWhenContentArrives() {
        let placeholder = VaultRowContextMenuRequest(rowId: "item", revision: nil)
        let content = VaultRowContextMenuRequest(rowId: "item", revision: 1)
        let pending = VaultRowActionsLoad(request: placeholder, generation: 1)
        let loaded = pending.resolving(actions, for: pending)

        XCTAssertTrue(loaded.actions(for: content).isEmpty)
    }
}
