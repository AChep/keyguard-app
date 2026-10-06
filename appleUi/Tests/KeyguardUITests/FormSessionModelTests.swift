import Foundation
import XCTest
@testable import KeyguardUI

final class FormSessionModelTests: XCTestCase {
    @MainActor
    func testTwoFormsKeepTheirDraftActionsAndCompletionIndependent() async throws {
        let firstSource = FormSessionSource()
        let secondSource = FormSessionSource()
        let first = makeModel { firstSource }
        let second = makeModel { secondSource }
        first.setTarget(true)
        second.setTarget(true)
        let firstOwner = try XCTUnwrap(first.identity)
        let secondOwner = try XCTUnwrap(second.identity)
        firstSource.publish?("first draft")
        secondSource.publish?("second draft")
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(first.detail, "first draft")
        XCTAssertEqual(second.detail, "second draft")

        first.perform(owner: firstOwner) { $0.edits.append("first edit") }
        second.perform(owner: secondOwner) { $0.edits.append("second edit") }
        firstSource.complete?()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(first.didComplete)
        XCTAssertFalse(second.didComplete)
        first.perform(owner: firstOwner) { $0.edits.append("after completion") }
        first.stop()
        secondSource.publish?("updated second draft")
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertNil(first.detail)
        XCTAssertEqual(second.detail, "updated second draft")
        XCTAssertEqual(firstSource.edits, ["first edit"])
        XCTAssertEqual(secondSource.edits, ["second edit"])
        XCTAssertEqual(firstSource.cancellations, 1)
        XCTAssertEqual(secondSource.cancellations, 0)
        second.stop()
    }

    @MainActor
    func testOldCompletionCannotDismissTheReopenedForm() async throws {
        var sources: [FormSessionSource] = []
        let model = makeModel {
            let source = FormSessionSource()
            sources.append(source)
            return source
        }
        model.setTarget(true)
        let oldOwner = try XCTUnwrap(model.identity)
        sources[0].complete?()
        model.stop()
        model.setTarget(true)
        sources[0].publish?("old draft")
        sources[0].complete?()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(model.didComplete)
        XCTAssertNil(model.detail)
        model.perform(owner: oldOwner) { $0.edits.append("old action") }
        XCTAssertTrue(sources[1].edits.isEmpty)

        sources[1].complete?()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(model.didComplete)
        model.stop()
        sources[1].complete?()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(model.didComplete)
        XCTAssertEqual(sources.map(\.cancellations), [1, 1])
    }

    @MainActor
    func testSynchronousCompletionIsDeliveredToTheCurrentForm() async throws {
        let model = DetailSessionModel<Bool, String, FormSessionSource>(
            makeSession: { _ in FormSessionSource() },
            subscribeWithCompletion: { _, onChange, onClose in
                onChange("ready")
                onClose()
                return BridgeObservation(cancel: {})
            }
        )
        model.setTarget(true)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(model.didComplete)
        XCTAssertEqual(model.detail, "ready")
        model.stop()
    }

    @MainActor
    func testCompletionCallbackDoesNotKeepTheFormAlive() {
        let source = FormSessionSource()
        var model: DetailSessionModel<Bool, String, FormSessionSource>? = makeModel { source }
        weak var weakModel = model
        model?.setTarget(true)
        model = nil
        XCTAssertNil(weakModel)
        XCTAssertEqual(source.cancellations, 1)
        source.complete?()
    }

    @MainActor
    private func makeModel(_ makeSource: @escaping () -> FormSessionSource)
        -> DetailSessionModel<Bool, String, FormSessionSource>
    {
        DetailSessionModel(
            makeSession: { _ in makeSource() },
            subscribeWithCompletion: { source, onChange, onClose in
                source.publish = onChange
                source.complete = onClose
                return BridgeObservation { source.cancellations += 1 }
            }
        )
    }
}

@MainActor
private final class FormSessionSource {
    var publish: ((String) -> Void)?
    var complete: (() -> Void)?
    var edits: [String] = []
    var cancellations = 0
}
