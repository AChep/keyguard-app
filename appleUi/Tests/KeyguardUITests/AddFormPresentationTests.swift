import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class AddFormPresentationTests: XCTestCase {
    @MainActor
    func testTwoEditorsKeepTheirSnapshotsCompletionAndPickersIndependent() async throws {
        let first = AddFormPresentation()
        let second = AddFormPresentation()
        let a = AddFormProbe()
        let b = AddFormProbe()
        first.start(makeModel: a.makeModel)
        second.start(makeModel: b.makeModel)
        let firstModel = try XCTUnwrap(first.model)
        let secondModel = try XCTUnwrap(second.model)
        firstModel.startAddFormObservation(observe: a.observe)
        secondModel.startAddFormObservation(observe: b.observe)
        a.publish(snapshot("Vault item"))
        b.publish(snapshot("Send"))
        var cancelledPickers: [String] = []
        firstModel.filePicker.enqueue(picker("vault", cancelled: { cancelledPickers.append($0) }))
        secondModel.filePicker.enqueue(picker("send", cancelled: { cancelledPickers.append($0) }))
        try await settle()
        XCTAssertEqual(firstModel.addForm.title, "Vault item")
        XCTAssertEqual(secondModel.addForm.title, "Send")
        a.complete()
        try await settle()
        XCTAssertTrue(firstModel.addFormDidSave)
        XCTAssertFalse(secondModel.addFormDidSave)
        XCTAssertEqual(cancelledPickers, ["vault"])
        XCTAssertEqual(secondModel.filePicker.pendingFilePicker?.requestId, "send")
        first.close()
        first.close()
        XCTAssertEqual(a.cancellations, 1)
        XCTAssertEqual(b.cancellations, 0)
        XCTAssertTrue(secondModel.isAddFormActive)
        XCTAssertEqual(secondModel.addForm.title, "Send")
        second.close()
        XCTAssertEqual(b.cancellations, 1)
        XCTAssertEqual(cancelledPickers, ["vault", "send"])
    }

    @MainActor
    func testReappearingUnderChildSheetKeepsTheDraftAndProducer() throws {
        let presentation = AddFormPresentation()
        let probe = AddFormProbe()
        presentation.start(makeModel: probe.makeModel)
        let model = try XCTUnwrap(presentation.model)
        model.startAddFormObservation(observe: probe.observe)
        presentation.start {
            XCTFail("A child sheet must not replace its editor"); return probe.makeModel()
        }
        XCTAssertTrue(presentation.model === model)
        XCTAssertEqual(probe.cancellations, 0)
        presentation.close()
        XCTAssertEqual(probe.cancellations, 1)
    }

    @MainActor
    func testQueuedSnapshotAndCompletionCannotReachReopenedEditor() async throws {
        let presentation = AddFormPresentation()
        let old = AddFormProbe()
        presentation.start(makeModel: old.makeModel)
        let oldModel = try XCTUnwrap(presentation.model)
        oldModel.startAddFormObservation(observe: old.observe)
        old.publish(snapshot("Old draft"))
        old.complete()
        presentation.close()
        let replacement = AddFormProbe()
        presentation.start(makeModel: replacement.makeModel)
        let newModel = try XCTUnwrap(presentation.model)
        newModel.startAddFormObservation(observe: replacement.observe)
        replacement.publish(snapshot("New draft"))
        try await settle()
        old.publish(snapshot("Late old draft"))
        old.complete()
        try await settle()
        XCTAssertFalse(oldModel.addForm.loaded)
        XCTAssertFalse(oldModel.addFormDidSave)
        XCTAssertEqual(newModel.addForm.title, "New draft")
        XCTAssertFalse(newModel.addFormDidSave)
        XCTAssertEqual(old.cancellations, 1)
        XCTAssertEqual(replacement.cancellations, 0)
        presentation.close()
    }

    private func snapshot(_ title: String) -> AddItemFormSnapshot {
        AddItemFormSnapshot(
            loaded: true, title: title, canSave: true, ownership: nil, merge: nil,
            items: [], actions: [], fileDropText: nil
        )
    }

    private func picker(_ id: String, cancelled: @escaping (String) -> Void) -> PendingFilePicker {
        PendingFilePicker(
            requestId: id, kind: .openDocument, mimeTypes: [], persistent: false,
            resolve: { _, _, _, _, _ in XCTFail("Dismissed picker must not resolve") }, cancel: cancelled
        )
    }

    private func settle() async throws { try await Task.sleep(for: .milliseconds(20)) }
}

@MainActor
private final class AddFormProbe {
    var publish: (AddItemFormSnapshot) -> Void = { _ in }
    var complete: () -> Void = {}
    var cancellations = 0

    func makeModel() -> AddFormModel {
        AddFormModel(coreProvider: { fatalError("Form tests must not access the user's vault") })
    }

    func observe(
        onChange: @escaping (AddItemFormSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation {
        publish = onChange
        complete = onClose
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }
}
