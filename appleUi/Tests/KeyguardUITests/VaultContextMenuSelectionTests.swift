import XCTest
@testable import KeyguardUI

final class VaultContextMenuSelectionTests: XCTestCase {
    func testMacContextualClickOutsideSelectionUsesRowActions() {
        let selection = VaultSelection(count: 2, actions: [], selectedIds: ["a", "b"])
        XCTAssertNil(selection.contextualItemIds(for: "c", minimumCount: 2))
        XCTAssertEqual(selection.contextualItemIds(for: "a", minimumCount: 2), ["a", "b"])
    }

    func testIOSSingleSelectionOnlyAppliesToItsOwnContextualRow() {
        let selection = VaultSelection(count: 1, actions: [], selectedIds: ["a"])
        XCTAssertNil(selection.contextualItemIds(for: "b", minimumCount: 1))
        XCTAssertEqual(selection.contextualItemIds(for: "a", minimumCount: 1), ["a"])
        XCTAssertNil(selection.contextualItemIds(for: "a", minimumCount: 2))
    }

    func testUnavailableMembershipNeverExposesBulkActions() {
        XCTAssertNil(VaultSelection(count: 2, actions: []).contextualItemIds(for: "a", minimumCount: 2))
        let selection = VaultSelection(count: 2, actions: [], selectedIds: ["a", "b"])
        XCTAssertNil(selection.contextualItemIds(for: nil, minimumCount: 2))
    }

    func testSameCountReplacementChangesContextualMembership() {
        let original = VaultSelection(count: 2, actions: [], selectedIds: ["a", "b"])
        let replacement = VaultSelection(count: 2, actions: [], selectedIds: ["b", "c"])
        XCTAssertNotEqual(original, replacement)
        XCTAssertNotEqual(original.selectedIds, replacement.selectedIds)
        XCTAssertNil(replacement.contextualItemIds(for: "a", minimumCount: 2))
        XCTAssertEqual(replacement.contextualItemIds(for: "c", minimumCount: 2), ["b", "c"])
    }
}
