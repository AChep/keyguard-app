import XCTest
@testable import KeyguardUI

final class BrowseSelectionTrackerTests: XCTestCase {
    func testLoadingDoesNotClearSelectionButCompletedEmptyResultsDo() {
        var tracker = BrowseSelectionTracker()
        XCTAssertFalse(tracker.shouldClear(detailId: 1, fromList: true, isPresent: true, isLoaded: true))
        XCTAssertFalse(tracker.shouldClear(detailId: 1, fromList: true, isPresent: false, isLoaded: false))
        XCTAssertTrue(tracker.shouldClear(detailId: 1, fromList: true, isPresent: false, isLoaded: true))
    }

    func testMissingDeepLinkStaysOpenAndDoesNotSelectAnotherItem() {
        var tracker = BrowseSelectionTracker()
        XCTAssertFalse(tracker.shouldClear(detailId: 1, fromList: false, isPresent: false, isLoaded: true))
    }

    func testPreviouslyVisibleDeepLinkIsClearedWhenFilteredOut() {
        var tracker = BrowseSelectionTracker()
        XCTAssertFalse(tracker.shouldClear(detailId: 1, fromList: false, isPresent: true, isLoaded: true))
        XCTAssertTrue(tracker.shouldClear(detailId: 1, fromList: false, isPresent: false, isLoaded: true))
        XCTAssertFalse(tracker.shouldClear(detailId: 2, fromList: false, isPresent: false, isLoaded: true))
    }

    func testClearedOrLockedSelectionDoesNotCarryAMatchIntoANewSession() {
        var tracker = BrowseSelectionTracker()
        XCTAssertFalse(tracker.shouldClear(detailId: 1, fromList: false, isPresent: true, isLoaded: true))
        XCTAssertFalse(tracker.shouldClear(detailId: nil, fromList: false, isPresent: false, isLoaded: false))
        XCTAssertFalse(tracker.shouldClear(detailId: 1, fromList: false, isPresent: false, isLoaded: true))
    }
}
