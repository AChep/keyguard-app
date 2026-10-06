import XCTest
@testable import KeyguardUI

final class NavigationTabTests: XCTestCase {
    @MainActor
    func testUpToFiveDestinationsNeedNoOverflow() {
        for count in 0...5 {
            let sections = Array(NavSection.defaults.prefix(count))
            let tabs = NavigationTab.items(for: sections)

            XCTAssertEqual(tabs.map(\.id), sections.map(\.key))
            XCTAssertTrue(tabs.allSatisfy { !$0.isOverflow })
            XCTAssertTrue(tabs.allSatisfy { !$0.isHidden(selectedKey: "vault") })
        }
    }

    @MainActor
    func testOverflowKeepsFourPrimaryTabsAndRevealsOnlyTheSelectedDestination() {
        let sections = NavSection.defaults
        let tabs = NavigationTab.items(for: sections)

        XCTAssertEqual(tabs.filter(\.isOverflow).map(\.id), ["watchtower", "settings"])
        for section in sections {
            let visible = tabs.filter { !$0.isHidden(selectedKey: section.key) }

            XCTAssertEqual(Array(visible.prefix(4)).map(\.id), Array(sections.prefix(4)).map(\.key))
            XCTAssertTrue(visible.contains { $0.id == section.key }, "Deep-linked destinations must be selectable")
            XCTAssertLessThanOrEqual(visible.count, 5, "The system More screen must never be needed")
            XCTAssertEqual(visible.count, tabs.first { $0.id == section.key }?.isOverflow == true ? 5 : 4)
        }
    }

    @MainActor
    func testLoadingSavedLayoutPreservesTabIdentityAndRevealsAllFiveDestinations() {
        let defaults = NavSection.defaults
        let savedKeys = ["vault", "generator", "watchtower", "gpg_tools", "settings"]
        let saved = savedKeys.compactMap { key in defaults.first { $0.key == key } }
        let before = Dictionary(
            uniqueKeysWithValues: NavigationTab.items(for: defaults).map { ($0.section.key, $0.id) })
        let after = NavigationTab.items(for: saved)

        XCTAssertEqual(after.map(\.id), savedKeys)
        for tab in after {
            XCTAssertEqual(before[tab.section.key], tab.id)
            XCTAssertFalse(tab.isHidden(selectedKey: "settings"))
        }
    }

    @MainActor
    func testReorderingTabsPreservesIdentityAndUsesConfiguredOverflowOrder() throws {
        let defaults = NavSection.defaults
        let reordered = defaults.filter { $0.key != "generator" } + defaults.filter { $0.key == "generator" }
        let before = NavigationTab.items(for: defaults)
        let after = NavigationTab.items(for: reordered)

        XCTAssertEqual(Set(before.map(\.id)), Set(after.map(\.id)))
        XCTAssertEqual(after.filter(\.isOverflow).map(\.id), ["settings", "generator"])
        let generator = try XCTUnwrap(after.first { $0.id == "generator" })
        XCTAssertFalse(generator.isHidden(selectedKey: "generator"))
    }
}
