import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class FeatureObservationTests: XCTestCase {
    @MainActor
    func testAddFormRejectsOldCompletion() async throws {
        var cancellations = 0
        var completions: [() -> Void] = []
        let model = AddFormModel(
            coreProvider: { fatalError("An observation test must not access the user's vault") }
        )
        for _ in 0..<2 {
            model.startAddFormObservation() { _, onClose in
                completions.append(onClose)
                return BridgeObservation(cancel: { cancellations += 1 })
            }
        }
        XCTAssertTrue(model.isAddFormActive)
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
        XCTAssertEqual(cancellations, 2)
    }

    @MainActor
    func testGpgResultsCannotCrossOperationOrDismissalBoundaries() async throws {
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "encrypt")
        model.startGpgToolsObservation(operation: "decrypt")
        let result = GpgToolsResultSnapshot(
            title: "Result", notes: [], outputLabel: nil, outputText: "Output",
            incognito: false, canCopy: true, canSave: false, id: "result", file: nil
        )
        source.results[0](result)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertNil(model.gpgToolsResult)
        source.results[1](result)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.gpgToolsResult?.id, "result")
        model.stopGpgToolsObservation()
        source.results[1](result)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertNil(model.gpgToolsResult)
        XCTAssertEqual(source.cancellations, 2)
    }
}
