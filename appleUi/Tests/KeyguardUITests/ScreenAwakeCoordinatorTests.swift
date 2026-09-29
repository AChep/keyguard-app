import Foundation
import XCTest
@testable import KeyguardUI

@MainActor
final class ScreenAwakeCoordinatorTests: XCTestCase {
    private var activeRequest: ScreenAwakeRequest {
        ScreenAwakeRequest(isVisible: true, isSceneActive: true, isUnlocked: true, isEnabled: true)
    }

    func testOverlappingDetailAndSheetKeepAwakeUntilBothClose() {
        var changes: [Bool] = []
        let coordinator = ScreenAwakeCoordinator { changes.append($0) }
        let detail = UUID()
        let sheet = UUID()

        coordinator.update(id: detail, request: activeRequest)
        coordinator.update(id: sheet, request: activeRequest)
        coordinator.update(id: sheet, request: activeRequest)
        coordinator.remove(id: detail)
        coordinator.remove(id: detail)
        XCTAssertEqual(changes, [true])

        coordinator.remove(id: sheet)
        coordinator.remove(id: sheet)
        XCTAssertEqual(changes, [true, false])
    }

    func testPreferenceChangeReleasesDetailButKeepsBarcodeAwake() {
        var changes: [Bool] = []
        let coordinator = ScreenAwakeCoordinator { changes.append($0) }
        let detail = UUID()
        let barcode = UUID()
        var detailRequest = activeRequest

        coordinator.update(id: detail, request: detailRequest)
        coordinator.update(id: barcode, request: activeRequest)
        detailRequest.isEnabled = false
        coordinator.update(id: detail, request: detailRequest)
        XCTAssertEqual(changes, [true])

        coordinator.remove(id: barcode)
        XCTAssertEqual(changes, [true, false])

        detailRequest.isEnabled = true
        coordinator.update(id: detail, request: detailRequest)
        XCTAssertEqual(changes, [true, false, true])
    }

    func testInactiveSceneCannotCancelAnotherActiveScenesRequest() {
        var changes: [Bool] = []
        let coordinator = ScreenAwakeCoordinator { changes.append($0) }
        let firstScene = UUID()
        let secondScene = UUID()
        var request = activeRequest

        coordinator.update(id: firstScene, request: request)
        coordinator.update(id: secondScene, request: request)
        request.isSceneActive = false
        coordinator.update(id: firstScene, request: request)
        XCTAssertEqual(changes, [true])

        coordinator.update(id: secondScene, request: request)
        XCTAssertEqual(changes, [true, false])

        request.isSceneActive = true
        coordinator.update(id: firstScene, request: request)
        XCTAssertEqual(changes, [true, false, true])
    }

    func testLockedVaultAndHiddenViewsDoNotKeepScreenAwake() {
        var changes: [Bool] = []
        let coordinator = ScreenAwakeCoordinator { changes.append($0) }
        let id = UUID()
        var request = activeRequest

        coordinator.update(id: id, request: request)
        request.isUnlocked = false
        coordinator.update(id: id, request: request)
        request.isSceneActive = false
        coordinator.update(id: id, request: request)
        request.isSceneActive = true
        coordinator.update(id: id, request: request)
        XCTAssertEqual(changes, [true, false])

        request.isUnlocked = true
        coordinator.update(id: id, request: request)
        request.isVisible = false
        coordinator.update(id: id, request: request)
        request.isEnabled = false
        coordinator.update(id: id, request: request)
        request.isEnabled = true
        coordinator.update(id: id, request: request)
        XCTAssertEqual(changes, [true, false, true, false])
    }

    func testDisabledPreferenceDoesNotPreventNormalSleepOnEntry() {
        var changes: [Bool] = []
        let coordinator = ScreenAwakeCoordinator { changes.append($0) }
        let id = UUID()
        var request = activeRequest
        request.isEnabled = false

        coordinator.update(id: id, request: request)
        coordinator.remove(id: id)
        XCTAssertTrue(changes.isEmpty)
    }
}
