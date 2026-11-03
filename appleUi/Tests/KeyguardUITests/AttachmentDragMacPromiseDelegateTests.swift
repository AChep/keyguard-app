#if os(macOS)
import AppKit
import UniformTypeIdentifiers
import XCTest
@testable import KeyguardUI

@MainActor
final class AttachmentDragMacPromiseDelegateTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("AttachmentDragMacPromiseDelegateTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }

    func testAcceptedSessionThenWriteRetiresAndFailsLaterWrites() {
        let promise = Promise()
        promise.delegate.sessionEnded(operation: .copy)
        XCTAssertFalse(promise.offer.isRetired)
        XCTAssertTrue(promise.write(to: file("first")))
        XCTAssertTrue(promise.offer.isRetired)
        XCTAssertEqual(promise.content.closeCount, 1)
        XCTAssertFalse(promise.write(to: file("second")))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file("second").path))
        XCTAssertEqual(promise.content.writeCount, 1)
    }

    func testWriteThenAcceptedSessionRetires() {
        let promise = Promise()
        XCTAssertTrue(promise.write(to: file("first")))
        XCTAssertFalse(promise.offer.isRetired)
        XCTAssertTrue(promise.write(to: file("again")))
        XCTAssertFalse(promise.offer.isRetired)
        promise.delegate.sessionEnded(operation: .copy)
        XCTAssertTrue(promise.offer.isRetired)
        XCTAssertEqual(promise.content.closeCount, 1)
        XCTAssertFalse(promise.write(to: file("late")))
    }

    func testFailedOrCancelledWritesDoNotDeliver() {
        for code in [CocoaError.Code.fileWriteUnknown, .userCancelled] {
            let promise = Promise(content: AttachmentDragTestContent(writeError: CocoaError(code)))
            XCTAssertFalse(promise.write(to: file("\(code.rawValue)")))
            promise.delegate.sessionEnded(operation: .copy)
            XCTAssertFalse(promise.write(to: file("\(code.rawValue)-again")))
            XCTAssertFalse(promise.offer.isRetired)
            XCTAssertEqual(promise.content.closeCount, 0)
            promise.offer.retire()
        }
    }

    func testFailedWriteThenWrittenFileRetires() throws {
        let promise = Promise()
        let taken = file("taken")
        try Data([9]).write(to: taken)
        promise.delegate.sessionEnded(operation: .copy)
        XCTAssertFalse(promise.write(to: taken))
        XCTAssertFalse(promise.offer.isRetired)
        XCTAssertTrue(promise.write(to: file("written")))
        XCTAssertTrue(promise.offer.isRetired)
    }

    func testRejectedSessionRetiresAtOnce() {
        let promise = Promise()
        promise.delegate.sessionEnded(operation: [])
        XCTAssertTrue(promise.offer.isRetired)
        XCTAssertEqual(promise.content.closeCount, 1)
        XCTAssertFalse(promise.write(to: file("late")))
        XCTAssertEqual(promise.content.writeCount, 0)
    }

    func testKotlinRetirementStillRetiresAnUndeliveredOffer() {
        let promise = Promise()
        promise.delegate.sessionEnded(operation: .copy)
        promise.content.expire()
        XCTAssertTrue(promise.offer.isRetired)
        XCTAssertFalse(promise.write(to: file("late")))
        XCTAssertEqual(promise.content.writeCount, 0)
    }

    private func file(_ name: String) -> URL {
        directory.appendingPathComponent(name)
    }

    /// One handed-off offer behind the production delegate, as the transfer store builds it.
    private struct Promise {
        let content: AttachmentDragTestContent
        let offer: AttachmentDragOffer
        let delegate: MacPromiseDelegate
        let provider: NSFilePromiseProvider

        @MainActor
        init(content: AttachmentDragTestContent = AttachmentDragTestContent()) {
            self.content = content
            offer = AttachmentDragOffer(content: content) {}
            offer.handoff()
            delegate = MacPromiseDelegate(offer: offer)
            provider = NSFilePromiseProvider(fileType: UTType.data.identifier, delegate: delegate)
        }

        /// The delegate replies before it returns. True when it reported the file written.
        func write(to url: URL) -> Bool {
            let results = AttachmentDragTestResults<Bool>()
            delegate.filePromiseProvider(provider, writePromiseTo: url) { error in
                results.append(error == nil)
            }
            XCTAssertEqual(results.values.count, 1)
            return results.values == [true]
        }
    }
}
#endif
