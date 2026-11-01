import Foundation
import UniformTypeIdentifiers
import XCTest
@testable import KeyguardUI

@MainActor
final class AttachmentDragProviderTests: XCTestCase {
    func testProductionRegistrationSupportsRepeatedActualFoundationDataLoads() async throws {
        for size in [0, 1024, 2 * 1024 * 1024] {
            let bytes = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 * 31 + 7) })
            let content = AttachmentDragTestContent(data: bytes)
            let preparer = AttachmentDragTestPreparer(content: { content })
            let provider = AttachmentDragProvider.make(preparer: preparer)
            XCTAssertEqual(provider.suggestedName, content.name)
            XCTAssertEqual(provider.registeredTypeIdentifiers.count, 1)
            XCTAssertTrue(provider.hasItemConformingToTypeIdentifier(UTType.data.identifier))
            for _ in 0..<2 {
                let received = try await loadData(provider)
                XCTAssertEqual(received, bytes)
            }
            XCTAssertEqual(preparer.prepareCount, 1)
            XCTAssertEqual(content.readCount, 2)
            XCTAssertFalse(content.readOnMainThread)
        }
    }

    func testFoundationConvertsOnlyRegisteredDataIntoItsOwnTemporaryFile() async throws {
        let bytes = Data((0..<1024).map { UInt8(truncatingIfNeeded: $0 * 31 + 7) })
        let content = AttachmentDragTestContent(data: bytes)
        let preparer = AttachmentDragTestPreparer(content: { content })
        let provider = AttachmentDragProvider.make(preparer: preparer)
        // This production factory registers only data. The temporary URL below belongs to Foundation.
        XCTAssertEqual(provider.registeredTypeIdentifiers.count, 1)
        XCTAssertTrue(provider.hasItemConformingToTypeIdentifier(UTType.data.identifier))
        XCTAssertEqual(provider.suggestedName, content.name)
        let filename = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<String, any Error>) in
            provider.loadFileRepresentation(forTypeIdentifier: UTType.data.identifier) { url, error in
                do {
                    if let error { throw error }
                    guard let url else { throw CocoaError(.fileReadUnknown) }
                    // Foundation deletes this file when the callback returns; inspect it only here.
                    XCTAssertTrue(url.isFileURL)
                    XCTAssertEqual(try Data(contentsOf: url), bytes)
                    continuation.resume(returning: url.lastPathComponent)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
        XCTAssertEqual(filename, content.name)
        XCTAssertEqual(preparer.prepareCount, 1)
        XCTAssertEqual(content.readCount, 1)
    }

    func testActualFoundationProgressCancelledBeforePreparationCompletesReturnsNoDataOnce() async {
        let began = expectation(description: "Foundation requested the representation")
        let backendCancelled = expectation(description: "preparation cancelled")
        let preparer = AttachmentDragTestPreparer(
            onPrepare: { began.fulfill() }, onCancel: { backendCancelled.fulfill() })
        let provider = AttachmentDragProvider.make(preparer: preparer)
        let completed = expectation(description: "cancelled completion exactly once")
        completed.assertForOverFulfill = true
        let progress = provider.loadDataRepresentation(forTypeIdentifier: UTType.data.identifier) { data, error in
            XCTAssertNil(data)
            XCTAssertNotNil(error)
            completed.fulfill()
        }
        await fulfillment(of: [began], timeout: 2)
        progress.cancel()
        await fulfillment(of: [completed, backendCancelled], timeout: 2)
        XCTAssertEqual(preparer.handle.cancelCount, 1)
        let late = AttachmentDragTestContent()
        preparer.complete(late)
        XCTAssertEqual(late.closeCount, 1)
        XCTAssertEqual(late.readCount, 0)
    }

    func testAlreadyCancelledFoundationProgressNeverReturnsPreparedBytes() async {
        let preparer = AttachmentDragTestPreparer()
        let provider = AttachmentDragProvider.make(preparer: preparer)
        let completed = expectation(description: "pre-cancelled completion exactly once")
        completed.assertForOverFulfill = true
        let progress = provider.loadDataRepresentation(forTypeIdentifier: UTType.data.identifier) { data, error in
            XCTAssertNil(data)
            XCTAssertNotNil(error)
            completed.fulfill()
        }
        progress.cancel()
        await fulfillment(of: [completed], timeout: 2)
        preparer.complete(AttachmentDragTestContent())
    }

    private func loadData(_ provider: NSItemProvider) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadDataRepresentation(forTypeIdentifier: UTType.data.identifier) { data, error in
                if let data {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: error ?? CocoaError(.fileReadUnknown))
                }
            }
        }
    }
}
