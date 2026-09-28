import KeyguardShared
import XCTest
@testable import KeyguardUI

final class VaultDeltaPumpTests: XCTestCase {
    @MainActor
    func testRestartDoesNotApplyBufferedFramesFromTheCancelledRun() async throws {
        let store = VaultRowStore()
        let pump = VaultDeltaPump(store: store)
        let old = pump.start()
        old.yield(frame(revision: 1))
        pump.stop()
        let current = pump.start()
        defer { pump.stop() }

        // Give the cancelled iterator a turn to resume its buffered next value.
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(store.structure.revision, 0)

        current.yield(frame(revision: 2))
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(store.structure.revision, 2)
    }

    @MainActor
    func testResetFrameAllowsSubsequentFramesInTheSameRun() async throws {
        let store = VaultRowStore()
        let pump = VaultDeltaPump(store: store)
        let stream = pump.start()
        defer { pump.stop() }
        stream.yield(frame(revision: 5))
        stream.yield(frame(revision: 0, isReset: true))
        stream.yield(frame(revision: 1))
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(store.structure.revision, 1)
    }

    private func frame(revision: Int64, isReset: Bool = false) -> VaultDelta {
        VaultDelta(
            bridged: VaultListDelta(
                revision: revision,
                baseRevision: -1,
                isFull: true,
                isReset: isReset,
                fullEntryIds: [],
                fullEntryKinds: [],
                ops: [],
                upserts: [],
                removedIds: [],
                decorationUpserts: [],
                decorationRemovedIds: [],
                decorationsReset: false,
                itemCount: 0,
                scrollAnchorId: "",
                scrollAnchorOffset: 0
            ))
    }
}
