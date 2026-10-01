import XCTest
@testable import KeyguardUI

final class ListDetailNavigationTests: XCTestCase {
    func testUnselectedRootsKeepAnEmptyDetail() {
        for root in [NavigationListKind.vault, .send] {
            let state = ListDetailNavigation(root: root, entries: [])
            XCTAssertEqual(state.kind, root)
            XCTAssertNil(state.detailRoot)
            XCTAssertTrue(state.sidebarPath.isEmpty)
            XCTAssertTrue(state.detailPath.isEmpty)
        }
    }

    func testSelectedItemAndItsHistoryStayInDetailColumn() {
        let state = ListDetailNavigation(root: .vault, entries: [.init(id: 1), .init(id: 2)])
        XCTAssertEqual(state.detailRoot, 1)
        XCTAssertEqual(state.detailPath, [2])
        XCTAssertTrue(state.sidebarPath.isEmpty)
    }

    func testWatchtowerDrillDownMakesTheFilteredListTheSidebar() {
        let state = ListDetailNavigation(
            root: nil, entries: [.init(id: 1), .init(id: 2, isVaultList: true), .init(id: 3), .init(id: 4)])
        XCTAssertEqual(state.kind, .vault)
        XCTAssertEqual(state.listEntryId, 2)
        XCTAssertEqual(state.sidebarPath, [1, 2])
        XCTAssertEqual(state.detailRoot, 3)
        XCTAssertEqual(state.detailPath, [4])
    }

    func testNewestNestedListOwnsSelectionAndBackRestoresThePreviousDetail() {
        let first = ListDetailNavigation.Entry(id: 1)
        let list = ListDetailNavigation.Entry(id: 2, isVaultList: true)
        let detail = ListDetailNavigation.Entry(id: 3)
        let nested = ListDetailNavigation.Entry(id: 4, isVaultList: true)
        let state = ListDetailNavigation(root: .vault, entries: [first, list, detail, nested])
        XCTAssertEqual(state.sidebarPath, [1, 2, 3, 4])
        XCTAssertNil(state.detailRoot)
        let back = ListDetailNavigation(root: .vault, entries: [first, list, detail])
        XCTAssertEqual(back.sidebarPath, [1, 2])
        XCTAssertEqual(back.detailRoot, 3)
    }

    func testOtherSectionsUseTheirExistingStackUntilOpeningAVaultList() {
        let state = ListDetailNavigation(root: nil, entries: [.init(id: 1), .init(id: 2)])
        XCTAssertNil(state.kind)
        XCTAssertNil(state.detailRoot)
    }

    func testDeepLinkNeedsNoListSelectionToPopulateDetail() {
        let vault = ListDetailNavigation(root: .vault, entries: [.init(id: 10)])
        let send = ListDetailNavigation(root: .send, entries: [.init(id: 20)])
        XCTAssertEqual(vault.detailRoot, 10)
        XCTAssertEqual(send.detailRoot, 20)
    }
}
