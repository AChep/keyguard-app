import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class FeatureObservationTests: XCTestCase {
    @MainActor
    func testTwoFactorCompletionDismissesBothLoginScreensAndResets() async throws {
        var requireTwofa: (() -> Void)?
        var completeTwofa: (() -> Void)?
        var starts = 0
        var cancellations = 0
        var model: BitwardenLoginModel? = BitwardenLoginModel(
            coreProvider: { fatalError("An observation test must not access the user's vault") },
            observeLogin: { _, _, onTwofa in
                starts += 1
                requireTwofa = onTwofa
                return BridgeObservation(cancel: { cancellations += 1 })
            },
            observeTwofa: { _, onSuccess in
                starts += 1
                completeTwofa = onSuccess
                return BridgeObservation(cancel: { cancellations += 1 })
            },
            openExternalURL: { _ in }
        )
        model?.startLoginObservation()
        model?.startLoginObservation()
        requireTwofa?()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model?.twofaActive, true)
        model?.startTwofaObservation()
        completeTwofa?()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model?.twofaDidSucceed, true)
        XCTAssertEqual(model?.loginDidSucceed, true)
        model?.stopTwofaObservation()
        model?.stopLoginObservation()
        XCTAssertEqual(model?.twofaActive, false)
        XCTAssertEqual(model?.twofaDidSucceed, false)
        XCTAssertEqual(model?.loginDidSucceed, false)
        XCTAssertEqual(starts, 2)
        XCTAssertEqual(cancellations, 2)
        weak var weakModel = model
        model = nil
        XCTAssertNil(weakModel)
    }

    @MainActor
    func testLoginSuccessQueuedBeforeStopDoesNotDismissTheNextLogin() async throws {
        var completeLogin: [() -> Void] = []
        let model = BitwardenLoginModel(
            coreProvider: { fatalError("An observation test must not access the user's vault") },
            observeLogin: { _, onSuccess, _ in
                completeLogin.append(onSuccess)
                return BridgeObservation(cancel: {})
            },
            observeTwofa: { _, _ in BridgeObservation(cancel: {}) },
            openExternalURL: { _ in }
        )
        model.startLoginObservation()
        completeLogin[0]()
        model.stopLoginObservation()
        model.startLoginObservation()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(model.loginDidSucceed)
        completeLogin[1]()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(model.loginDidSucceed)
    }

    @MainActor
    func testAddFormRejectsOldCompletionAndClearsTheMatchingEditRequest() async throws {
        var clearedRequests: [String] = []
        var cancellations = 0
        var completions: [() -> Void] = []
        let model = AddItemModel(
            coreProvider: { fatalError("An observation test must not access the user's vault") },
            closeLinkPicker: {},
            clearEditForm: { clearedRequests.append($0) }
        )
        for request in ["first", "second"] {
            model.startAddFormObservation(requestId: request) { _, onClose in
                completions.append(onClose)
                return BridgeObservation(cancel: { cancellations += 1 })
            }
        }
        XCTAssertTrue(model.isAddFormActive)
        XCTAssertEqual(clearedRequests, ["first"])
        completions[0]()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(model.addFormDidSave)
        completions[1]()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(model.addFormDidSave)
        model.stopAddFormObservation()
        completions[1]()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(model.addFormDidSave)
        XCTAssertFalse(model.isAddFormActive)
        XCTAssertEqual(clearedRequests, ["first", "second"])
        XCTAssertEqual(cancellations, 2)
    }

    @MainActor
    func testGpgResultsCannotCrossOperationOrDismissalBoundaries() async throws {
        var results: [(GpgToolsResultSnapshot) -> Void] = []
        var cancellations = 0
        let model = GpgToolsModel(
            coreProvider: { fatalError("An observation test must not access the user's vault") },
            observe: { _, _, onResult, _, _ in
                results.append(onResult)
                return BridgeObservation(cancel: { cancellations += 1 })
            },
            stop: {}
        )
        model.startGpgToolsObservation(operation: "encrypt")
        model.startGpgToolsObservation(operation: "decrypt")
        let result = GpgToolsResultSnapshot(
            title: "Result", notes: [], outputLabel: nil, outputText: "Output",
            incognito: false, canCopy: true, canSave: false, id: "result", file: nil
        )
        results[0](result)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertNil(model.gpgToolsResult)
        results[1](result)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.gpgToolsResult?.id, "result")
        model.stopGpgToolsObservation()
        results[1](result)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertNil(model.gpgToolsResult)
        XCTAssertEqual(cancellations, 2)
    }
}
