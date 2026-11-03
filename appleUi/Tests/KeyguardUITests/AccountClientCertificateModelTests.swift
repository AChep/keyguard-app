import XCTest
import KeyguardShared
@testable import KeyguardUI

@MainActor
final class AccountClientCertificateModelTests: XCTestCase {
    func testReappearingPreservesDraftAndObservation() {
        let source = FakeCertificateSource()
        let model = AccountClientCertificateModel(session: source)
        model.start()
        let draft = AccountClientCertificateSnapshot(
            configured: true, displayName: "Unsaved certificate", issuerName: nil, status: .expired,
            validFrom: nil, expiresOn: nil, progress: nil, details: [], unsaved: true,
            note: nil, noteSeverity: nil, busy: false, canImport: true, canRemove: true,
            canSave: true, confirmRemoval: false, dropText: nil)
        source.onChange?(draft)

        // Tab changes retain the screen's state and call start again on return.
        model.start()
        XCTAssertTrue(model.snapshot === draft)
        XCTAssertEqual(source.observations, 1)
        XCTAssertEqual(source.cancellations, 0)
        XCTAssertEqual(source.closes, 0)
        model.save()
        XCTAssertEqual(source.saves, 1)
    }

    func testReleasingEditorClosesSessionAndObservation() {
        let source = FakeCertificateSource()
        var model: AccountClientCertificateModel? = AccountClientCertificateModel(session: source)
        model?.start()
        weak var released = model
        model = nil

        XCTAssertNil(released)
        XCTAssertEqual(source.cancellations, 1)
        XCTAssertEqual(source.closes, 1)
    }

    func testSynchronousCompletionCancelsObservationAndIgnoresLaterActions() {
        let source = FakeCertificateSource()
        source.completeImmediately = true
        let model = AccountClientCertificateModel(session: source)
        model.start()
        XCTAssertTrue(model.finished)
        XCTAssertEqual(source.cancellations, 1)
        model.save()
        XCTAssertEqual(source.saves, 0)
        model.close()
        XCTAssertEqual(source.closes, 1)
    }

    func testClosingDropsCallbacksAndCancelsOnlyOnce() {
        let source = FakeCertificateSource()
        let model = AccountClientCertificateModel(session: source)
        model.start()
        model.close()
        model.close()
        source.onClose?()
        model.start()
        model.save()
        model.dropFile(url: URL(fileURLWithPath: "/tmp/late.p12"))
        model.invokeAction("late")
        XCTAssertEqual(source.observations, 1)
        XCTAssertEqual(source.cancellations, 1)
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.saves, 0)
        XCTAssertEqual(source.drops, 0)
        XCTAssertEqual(source.actions, 0)
        XCTAssertNil(model.snapshot)
    }
}

@MainActor
private final class FakeCertificateSource: AccountClientCertificateSource {
    var completeImmediately = false
    var onChange: ((AccountClientCertificateSnapshot) -> Void)?
    var onClose: (() -> Void)?
    var observations = 0
    var cancellations = 0
    var closes = 0
    var saves = 0
    var drops = 0
    var actions = 0

    func subscribe(
        onChange: @escaping (AccountClientCertificateSnapshot) -> Void,
        onClose: @escaping () -> Void
    ) -> BridgeObservation {
        observations += 1
        self.onChange = onChange
        self.onClose = onClose
        if completeImmediately { onClose() }
        return BridgeObservation(cancel: { [weak self] in self?.cancellations += 1 })
    }
    func setFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Void)?) {}
    func resolveFilePicker(requestId: String, uri: String, name: String?, size: Int64, accessToken: String?) {}
    func cancelFilePicker(requestId: String) {}
    func importCertificate() {}
    func dropFile(uri: String, name: String?, size: Int64) { drops += 1 }
    func removeCertificate() {}
    func save() { saves += 1 }
    func confirmRemoval() {}
    func dismissRemoval() {}
    func invokeAction(id: String) { actions += 1 }
    func close() { closes += 1 }
}
