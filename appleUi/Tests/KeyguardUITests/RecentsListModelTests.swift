import KeyguardShared
import XCTest
@testable import KeyguardUI

final class RecentsListModelTests: XCTestCase {
    @MainActor
    func testTwoSheetsKeepTabsRowsAndTotpIndependentWhenOneCloses() async throws {
        let a = RecentsProbe()
        let b = RecentsProbe()
        let first = model { a }
        let second = model { b }
        first.start()
        first.start()
        second.start()
        XCTAssertEqual(a.starts, 1)
        a.tabs?(tabs("recent"))
        b.tabs?(tabs("popular"))
        a.list?(frame(1))
        b.list?(frame(2))
        a.totp?(["row": .companion.loading])
        b.totp?(["row": .companion.error])
        try await settle()
        XCTAssertEqual(first.selectedTabKey, "recent")
        XCTAssertEqual(second.selectedTabKey, "popular")
        XCTAssertEqual(first.store.structure.revision, 1)
        XCTAssertEqual(second.store.structure.revision, 2)
        XCTAssertTrue(first.totpStates["row"] === TotpFieldSnapshot.companion.loading)
        XCTAssertTrue(second.totpStates["row"] === TotpFieldSnapshot.companion.error)

        first.setTab(key: "popular")
        first.stop()
        first.setTab(key: "closed")
        a.tabs?(tabs("late"))
        a.list?(frame(99))
        a.totp?(["late": .companion.error])
        second.setTab(key: "recent")
        b.tabs?(tabs("recent"))
        b.list?(frame(3))
        try await settle()
        XCTAssertEqual(a.commands, ["popular"])
        XCTAssertEqual(b.commands, ["recent"])
        XCTAssertEqual(a.cancellations, 1)
        XCTAssertEqual(b.cancellations, 0)
        XCTAssertFalse(first.loaded)
        XCTAssertTrue(first.tabs.isEmpty)
        XCTAssertTrue(first.totpStates.isEmpty)
        XCTAssertEqual(first.store.structure.revision, 0)
        XCTAssertEqual(second.store.structure.revision, 3)
        XCTAssertEqual(second.selectedTabKey, "recent")
        let closedActions = await first.rowActions(rowId: "row")
        XCTAssertTrue(closedActions.isEmpty)
        second.stop()
    }

    @MainActor
    func testRestartRejectsBufferedDeltasAndSmallSnapshotsFromOldSession() async throws {
        let old = RecentsProbe()
        let current = RecentsProbe()
        var sources = [old, current]
        let model = model { sources.removeFirst() }
        model.start()
        old.tabs?(tabs("queued"))
        old.list?(frame(9))
        old.totp?(["old": .companion.loading])
        model.stop()
        model.start()
        old.tabs?(tabs("late"))
        old.list?(frame(99))
        old.totp?(["late": .companion.error])
        current.tabs?(tabs("current"))
        current.list?(frame(2))
        current.totp?(["current": .companion.loading])
        try await settle()
        XCTAssertEqual(model.selectedTabKey, "current")
        XCTAssertEqual(model.store.structure.revision, 2)
        XCTAssertEqual(Set(model.totpStates.keys), ["current"])
        XCTAssertEqual(old.cancellations, 1)
        model.stop()
        model.stop()
        XCTAssertEqual(current.cancellations, 1)
    }

    @MainActor
    func testReleasingSheetCancelsItsSource() {
        let source = RecentsProbe()
        weak var weakModel: RecentsListModel?
        do {
            let model = model { source }
            weakModel = model
            model.start()
        }
        XCTAssertNil(weakModel)
        XCTAssertEqual(source.cancellations, 1)
    }

    @MainActor
    private func model(_ makeSession: @escaping () -> any RecentsSessionSource) -> RecentsListModel {
        RecentsListModel(makeSession: makeSession, onCopy: { _, _, _ in }, onReveal: { _ in })
    }

    private func tabs(_ key: String) -> RecentsTabsSnapshot {
        RecentsTabsSnapshot(loaded: true, tabs: [RecentsTabSnapshot(key: key, title: key)], selectedTabKey: key)
    }

    private func frame(_ revision: Int64) -> VaultDelta {
        VaultDelta(
            bridged: VaultListDelta(
                revision: revision, baseRevision: -1, isFull: true, isReset: false,
                fullEntryIds: [], fullEntryKinds: [], ops: [], upserts: [], removedIds: [],
                decorationUpserts: [], decorationRemovedIds: [], decorationsReset: false,
                itemCount: 0, scrollAnchorId: "", scrollAnchorOffset: 0
            ))
    }

    private func settle() async throws { try await Task.sleep(for: .milliseconds(30)) }
}

@MainActor
private final class RecentsProbe: RecentsSessionSource {
    var list: ((VaultDelta) -> Void)?
    var tabs: ((RecentsTabsSnapshot) -> Void)?
    var totp: (([String: TotpFieldSnapshot]) -> Void)?
    var commands: [String] = []
    var cancellations = 0
    var starts = 0

    func subscribe(
        onListDelta: @escaping (VaultDelta) -> Void,
        onTabs: @escaping (RecentsTabsSnapshot) -> Void,
        onTotp: @escaping ([String: TotpFieldSnapshot]) -> Void
    ) -> BridgeObservation {
        starts += 1
        list = onListDelta
        tabs = onTabs
        totp = onTotp
        return BridgeObservation { [weak self] in self?.cancellations += 1 }
    }

    func setTab(key: String) { commands.append(key) }
}
