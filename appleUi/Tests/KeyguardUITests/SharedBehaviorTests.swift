import XCTest
@testable import KeyguardUI

final class SharedBehaviorTests: XCTestCase {
    func testNavigationSelectionUsesKeysAndScopes() {
        let sections = NavSection.defaults
        XCTAssertEqual(UnlockedNavigationSelection.section(for: "sends", in: sections).scope, "send")
        XCTAssertEqual(UnlockedNavigationSelection.section(forScope: "send", in: sections).key, "sends")
        XCTAssertEqual(UnlockedNavigationSelection.section(for: "removed", in: sections).key, "vault")
        XCTAssertEqual(UnlockedNavigationSelection.section(forScope: "removed", in: sections).key, "vault")
        let withoutVault = sections.filter { $0.key != "vault" }
        XCTAssertEqual(UnlockedNavigationSelection.section(for: nil, in: withoutVault), withoutVault[0])
        let withoutSends = sections.filter { $0.key != "sends" }
        XCTAssertEqual(UnlockedNavigationSelection.section(for: "sends", in: withoutSends).key, "vault")
    }

    func testProjectionCachesAndDistinguishesStructureChanges() {
        var projection = VaultListProjection()
        let rows = [VaultRowEntry(id: "a", kind: .item)]
        XCTAssertTrue(projection.update(entries: rows, revision: 1, options: .init()).idsChanged)
        // An unchanged revision must not project again, even if a caller supplies other entries.
        XCTAssertFalse(projection.update(entries: [], revision: 1, options: .init()).needsReconfiguration)
        XCTAssertEqual(projection.ids, ["a"])
        XCTAssertFalse(projection.update(entries: rows, revision: 2, options: .init()).needsReconfiguration)
        let changed = [VaultRowEntry(id: "a", kind: .quickFilters)]
        let result = projection.update(entries: changed, revision: 3, options: .init())
        XCTAssertTrue(result.entriesChanged)
        XCTAssertFalse(result.idsChanged)
        XCTAssertEqual(projection.entriesById["a"]?.kind, .quickFilters)
        XCTAssertTrue(projection.update(entries: [], revision: 4, options: .init()).idsChanged)
        XCTAssertTrue(projection.entriesById.isEmpty)
    }

    func testProjectionPlatformOptionsInvalidateWithoutRevisionChange() {
        var projection = VaultListProjection()
        let rows = [VaultRowEntry(id: "filters", kind: .quickFilters), VaultRowEntry(id: "a", kind: .item)]
        _ = projection.update(entries: rows, revision: 1, options: .init(includesQuickFilters: false))
        XCTAssertEqual(projection.ids, ["a"])
        _ = projection.update(entries: rows, revision: 1, options: .init())
        XCTAssertEqual(projection.ids, ["filters", "a"])
        let header = projection.update(entries: rows, revision: 1, options: .init(leadingId: "header"))
        XCTAssertFalse(header.entriesChanged)
        XCTAssertTrue(header.idsChanged)
        XCTAssertEqual(projection.ids, ["header", "filters", "a"])
        XCTAssertNil(projection.entriesById["header"])
        XCTAssertTrue(projection.update(entries: rows, revision: 1, options: .init()).idsChanged)
    }

    func testEmptyStateRequiresPublishedMarkerWithoutItems() {
        let marker = VaultRowEntry(id: "empty", kind: .noItems)
        let filters = VaultRowEntry(id: "filters", kind: .quickFilters)
        let section = VaultRowEntry(id: "section", kind: .section)
        let suggestions = VaultRowEntry(id: "suggestions", kind: .noSuggestions)
        XCTAssertFalse(VaultListProjection.hasNoItems(in: []))
        XCTAssertFalse(VaultListProjection.hasNoItems(in: [filters, section, suggestions]))
        XCTAssertTrue(VaultListProjection.hasNoItems(in: [filters, section, marker]))
        XCTAssertFalse(VaultListProjection.hasNoItems(in: [marker, VaultRowEntry(id: "item", kind: .item)]))
    }

    func testNativeEmptyStatePreservesHeaderFiltersAndOtherMarkers() {
        var projection = VaultListProjection()
        let rows = [
            VaultRowEntry(id: "filters", kind: .quickFilters),
            VaultRowEntry(id: "section", kind: .section),
            VaultRowEntry(id: "suggestions", kind: .noSuggestions),
            VaultRowEntry(id: "empty", kind: .noItems),
        ]
        _ = projection.update(entries: rows, revision: 1, options: .init(leadingId: "sync"))
        XCTAssertEqual(projection.ids, ["sync", "filters", "section", "suggestions", "empty"])

        let changed = projection.update(
            entries: rows, revision: 1, options: .init(usesNativeEmptyState: true, leadingId: "sync"))
        XCTAssertTrue(changed.idsChanged)
        XCTAssertEqual(projection.ids, ["sync", "filters", "section", "suggestions"])
        XCTAssertNil(projection.entriesById["empty"])

        _ = projection.update(
            entries: rows, revision: 1,
            options: .init(includesQuickFilters: false, usesNativeEmptyState: true))
        XCTAssertEqual(projection.ids, ["section", "suggestions"])

        _ = projection.update(entries: rows, revision: 1, options: .init())
        XCTAssertEqual(projection.ids, ["filters", "section", "suggestions", "empty"])
    }

    func testNativeEmptyStateTransitionsKeepMarkersAlongsideRealItems() {
        var projection = VaultListProjection()
        let marker = VaultRowEntry(id: "empty", kind: .noItems)
        let item = VaultRowEntry(id: "item", kind: .item)
        let options = VaultListProjection.Options(usesNativeEmptyState: true)
        _ = projection.update(entries: [marker], revision: 1, options: options)
        XCTAssertTrue(projection.ids.isEmpty)
        _ = projection.update(entries: [marker, item], revision: 2, options: options)
        XCTAssertEqual(projection.ids, ["empty", "item"])
        _ = projection.update(entries: [marker], revision: 3, options: options)
        XCTAssertTrue(projection.ids.isEmpty)
        _ = projection.update(entries: [], revision: 4, options: options)
        XCTAssertTrue(projection.entriesById.isEmpty)
    }

    @MainActor
    func testScrollReportingGatesThrottlesAndCancels() {
        var time = Date(timeIntervalSince1970: 100)
        var anchor: String? = "a"
        var reports: [String] = []
        var pending: (@MainActor () -> Void)?
        var cancellations = 0
        var reporter: VaultScrollReporter? = VaultScrollReporter(
            now: { time },
            schedule: { callback in
                pending = callback
                return {
                    cancellations += 1; pending = nil
                }
            },
            visibleAnchor: { anchor },
            report: { reports.append($0) }
        )
        reporter?.didScroll()
        XCTAssertTrue(reports.isEmpty)
        reporter?.isEnabled = true
        reporter?.didScroll()
        XCTAssertEqual(reports, ["a"])
        time += 0.05
        anchor = "b"
        reporter?.didScroll()
        XCTAssertEqual(reports, ["a"])
        anchor = "c"
        reporter?.didScroll()
        XCTAssertEqual(cancellations, 1)
        time += 0.2
        pending?()
        XCTAssertEqual(reports, ["a", "c"])
        time += 0.3
        reporter?.didScroll()
        XCTAssertEqual(reports, ["a", "c"])
        time += 0.05
        anchor = "d"
        reporter?.didScroll()
        XCTAssertNotNil(pending)
        reporter = nil
        XCTAssertNil(pending)
        XCTAssertEqual(reports, ["a", "c"])
    }
}
