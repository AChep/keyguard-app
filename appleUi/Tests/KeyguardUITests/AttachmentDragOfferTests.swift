import Foundation
import XCTest
@testable import KeyguardUI

final class AttachmentDragOfferTests: XCTestCase {
    func testRetirementRejectsNewReadersAndLetsAdmittedReadersFinish() async throws {
        try await Task.detached {
            let content = AttachmentDragTestContent()
            let changes = AttachmentDragTestResults<Bool>()
            let offer = AttachmentDragOffer(content: content) { changes.append(true) }
            XCTAssertIdentical(try XCTUnwrap(offer.admit()), content)
            XCTAssertNotNil(offer.admit())
            offer.retire()
            XCTAssertNil(offer.admit())
            XCTAssertTrue(offer.isRetired)
            XCTAssertTrue(offer.hasReaders)
            offer.readerFinished()
            offer.readerFinished()
            XCTAssertFalse(offer.hasReaders)
            XCTAssertEqual(content.closeCount, 1)
            XCTAssertEqual(changes.values.count, 3)
        }.value
    }

    func testFinishedReaderDoesNotRetireTheOffer() async {
        await Task.detached {
            let content = AttachmentDragTestContent()
            let offer = AttachmentDragOffer(content: content) {}
            defer { offer.retire() }
            for _ in 0..<2 {
                XCTAssertNotNil(offer.admit())
                offer.readerFinished()
                XCTAssertFalse(offer.hasReaders)
                XCTAssertFalse(offer.isRetired)
                XCTAssertTrue(content.isAvailable())
            }
        }.value
    }

    func testKotlinRetirementAfterHandoffRetiresTheOffer() {
        let content = AttachmentDragTestContent()
        let changes = AttachmentDragTestResults<Bool>()
        let offer = AttachmentDragOffer(content: content) { changes.append(true) }
        offer.handoff()
        XCTAssertEqual(content.handoffCount, 1)
        XCTAssertFalse(offer.isRetired)
        content.expire()
        XCTAssertTrue(offer.isRetired)
        XCTAssertNil(offer.admit())
        XCTAssertEqual(content.closeCount, 1)
        XCTAssertEqual(changes.values.count, 1)
    }

    func testHandingOffExpiredContentRetiresTheOfferAtOnce() {
        let content = AttachmentDragTestContent()
        let offer = AttachmentDragOffer(content: content) {}
        content.expire()
        offer.handoff()
        XCTAssertTrue(offer.isRetired)
        XCTAssertEqual(content.closeCount, 1)
    }

    func testPromisedReaderWaitsForPreparationThenKotlinRetiresIt() async throws {
        let preparer = AttachmentDragTestPreparer(promisedFileName: "attachment.bin")
        let offer = AttachmentDragOffer(promising: "attachment.bin") {}
        offer.prepare(with: preparer)
        XCTAssertEqual(preparer.prepareCount, 1)
        XCTAssertTrue(offer.isPreparing)
        let admitted = Task.detached { offer.admit() }
        let content = AttachmentDragTestContent()
        preparer.complete(content)
        let reader = await admitted.value
        XCTAssertIdentical(try XCTUnwrap(reader), content)
        XCTAssertEqual(content.handoffCount, 1)
        XCTAssertEqual(preparer.handle.cancelCount, 0)
        offer.readerFinished()
        content.expire()
        XCTAssertTrue(offer.isRetired)
        XCTAssertEqual(content.closeCount, 1)
    }

    func testRetiringAPromisedOfferCancelsPreparationAndWakesReaders() async {
        let preparer = AttachmentDragTestPreparer(promisedFileName: "attachment.bin")
        let offer = AttachmentDragOffer(promising: "attachment.bin") {}
        offer.prepare(with: preparer)
        let admitted = Task.detached { offer.admit() == nil }
        offer.retire()
        let rejected = await admitted.value
        XCTAssertTrue(rejected)
        XCTAssertEqual(preparer.handle.cancelCount, 1)
        let late = AttachmentDragTestContent()
        preparer.complete(late)
        XCTAssertEqual(late.closeCount, 1)
        XCTAssertEqual(late.handoffCount, 0)
    }

    func testFailedOrExpiredPreparationRetiresAndRejectsReaders() async {
        for content in [nil, AttachmentDragTestContent()] {
            content?.expire()
            let preparer = AttachmentDragTestPreparer(promisedFileName: "attachment.bin")
            let changes = AttachmentDragTestResults<Bool>()
            let offer = AttachmentDragOffer(promising: "attachment.bin") { changes.append(true) }
            offer.prepare(with: preparer)
            let admitted = Task.detached { offer.admit() == nil }
            preparer.complete(content)
            let rejected = await admitted.value
            XCTAssertTrue(rejected)
            XCTAssertTrue(offer.isRetired)
            XCTAssertEqual(changes.values.count, 1)
            if let content {
                XCTAssertEqual(content.closeCount, 1)
                XCTAssertEqual(content.handoffCount, 0)
            }
        }
    }

    func testSynchronousPreparationPublishesWithoutCancellingItsHandle() {
        let content = AttachmentDragTestContent()
        let preparer = AttachmentDragTestPreparer(content: { content })
        let offer = AttachmentDragOffer(promising: preparer.name) {}
        defer { offer.retire() }
        offer.prepare(with: preparer)
        XCTAssertFalse(offer.isPreparing)
        XCTAssertEqual(preparer.handle.cancelCount, 0)
        XCTAssertEqual(content.handoffCount, 1)
    }

    func testWaitersSettleOnceWithTheContentOrNil() {
        let preparer = AttachmentDragTestPreparer()
        let offer = AttachmentDragOffer(promising: preparer.name) {}
        let settled = AttachmentDragTestResults<Bool>()
        offer.whenSettled { settled.append($0 != nil) }
        offer.prepare(with: preparer)
        XCTAssertEqual(settled.values, [])
        preparer.complete(AttachmentDragTestContent())
        XCTAssertEqual(settled.values, [true])
        offer.whenSettled { settled.append($0 != nil) }
        XCTAssertEqual(settled.values, [true, true])
        offer.retire()
        offer.whenSettled { settled.append($0 != nil) }
        XCTAssertEqual(settled.values, [true, true, false])
    }
}
