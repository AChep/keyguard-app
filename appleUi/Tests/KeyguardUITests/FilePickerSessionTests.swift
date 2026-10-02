import Foundation
import XCTest
import KeyguardShared
@testable import KeyguardUI

final class FilePickerSessionTests: XCTestCase {
    @MainActor
    func testClosingOnePickerLeavesTheOtherRequestActive() {
        let first = FilePickerSession()
        let second = FilePickerSession()
        var cancellations: [String] = []
        let firstRequest = request("first", cancel: { cancellations.append($0) })
        let secondRequest = request("second", cancel: { cancellations.append($0) })
        first.enqueue(firstRequest)
        second.enqueue(secondRequest)
        first.cancel()
        first.cancel(requestID: firstRequest.id)
        XCTAssertEqual(cancellations, ["first"])
        XCTAssertNil(first.pendingFilePicker)
        XCTAssertEqual(second.pendingFilePicker?.id, secondRequest.id)
        second.cancel()
        XCTAssertEqual(cancellations, ["first", "second"])
    }

    @MainActor
    func testOldImporterCannotConsumeReplacementEvenWithTheSameBridgeRequestID() {
        let picker = FilePickerSession()
        var cancellations = 0
        var resolutions = 0
        let old = request("same", resolve: { resolutions += 1 }, cancel: { _ in cancellations += 1 })
        let replacement = request("same", resolve: { resolutions += 1 }, cancel: { _ in cancellations += 1 })
        picker.enqueue(old)
        picker.enqueue(replacement)
        picker.resolve(result: .success([URL(fileURLWithPath: #filePath)]), requestID: old.id)
        picker.cancel(requestID: old.id)
        XCTAssertEqual(cancellations, 1)
        XCTAssertEqual(resolutions, 0)
        XCTAssertEqual(picker.pendingFilePicker?.id, replacement.id)
        picker.cancel()
        XCTAssertEqual(cancellations, 2)
    }

    @MainActor
    func testResolutionConsumesOnlyItsOwnersRequestAndRunsOnce() {
        let first = FilePickerSession()
        let second = FilePickerSession()
        var resolutions: [String] = []
        var cancellations: [String] = []
        let a = request("a", resolve: { resolutions.append("a") }, cancel: { cancellations.append($0) })
        let b = request("b", resolve: { resolutions.append("b") }, cancel: { cancellations.append($0) })
        first.enqueue(a)
        second.enqueue(b)
        let result: Result<[URL], Error> = .success([URL(fileURLWithPath: #filePath)])
        first.resolve(result: result, requestID: b.id)
        first.resolve(result: result, requestID: a.id)
        first.resolve(result: result, requestID: a.id)
        first.cancel(requestID: a.id)
        XCTAssertEqual(resolutions, ["a"])
        XCTAssertTrue(cancellations.isEmpty)
        XCTAssertEqual(second.pendingFilePicker?.id, b.id)
        second.cancel()
    }

    @MainActor
    func testLateSelectionAfterDismissalCannotCallBackIntoTheClosedWizard() {
        let picker = FilePickerSession()
        var resolutions = 0
        var cancellations = 0
        let pending = request("wizard", resolve: { resolutions += 1 }, cancel: { _ in cancellations += 1 })
        picker.enqueue(pending)
        picker.cancel()
        picker.resolve(result: .success([URL(fileURLWithPath: #filePath)]), requestID: pending.id)
        XCTAssertEqual(resolutions, 0)
        XCTAssertEqual(cancellations, 1)
    }

    @MainActor
    func testDatabaseExportRejectsOldDelegatesAndPreservesOriginalURLAndBookmark() throws {
        let picker = FilePickerSession()
        var cancellations = 0
        var resolutions: [(String, String?)] = []
        func pending() -> PendingFilePicker {
            PendingFilePicker(
                requestId: "database", kind: .theNewDocument, mimeTypes: [], persistent: true,
                suggestedName: "Vault.kdbx",
                resolve: { _, uri, _, _, token in resolutions.append((uri, token)) },
                cancel: { _ in cancellations += 1 }
            )
        }
        picker.enqueue(pending())
        let old = try XCTUnwrap(picker.pendingFileExport)
        picker.enqueue(pending())
        let current = try XCTUnwrap(picker.pendingFileExport)
        old.resolve(old.requestId, "file:///old.kdbx", "old.kdbx", 0, "old-bookmark")
        old.cancel(old.requestId)
        XCTAssertEqual(cancellations, 1)
        XCTAssertTrue(resolutions.isEmpty)
        XCTAssertEqual(picker.pendingFileExport?.id, current.id)
        current.resolve(current.requestId, "file:///Vault.kdbx", "Vault.kdbx", 0, "bookmark")
        current.resolve(current.requestId, "file:///Vault.kdbx", "Vault.kdbx", 0, "bookmark")
        XCTAssertEqual(resolutions.count, 1)
        XCTAssertEqual(resolutions.first?.0, "file:///Vault.kdbx")
        XCTAssertEqual(resolutions.first?.1, "bookmark")
        XCTAssertNil(picker.pendingFilePicker)
        XCTAssertNil(picker.pendingFileExport)
    }

    @MainActor
    func testDismissingDatabaseCreationCancelsItsExportAndRejectsLateSave() throws {
        let picker = FilePickerSession()
        var cancellations = 0
        picker.enqueue(
            PendingFilePicker(
                requestId: "database", kind: .theNewDocument, mimeTypes: [], persistent: true,
                resolve: { _, _, _, _, _ in XCTFail("Dismissed login must not accept a file") },
                cancel: { _ in cancellations += 1 }
            ))
        let export = try XCTUnwrap(picker.pendingFileExport)
        picker.cancel()
        export.resolve(export.requestId, "file:///late.kdbx", nil, 0, "bookmark")
        export.cancel(export.requestId)
        XCTAssertEqual(cancellations, 1)
        XCTAssertNil(picker.pendingFileExport)
    }

    @MainActor
    func testPersistentDatabaseSelectionKeepsItsOriginalURL() {
        let picker = FilePickerSession()
        let original = URL(fileURLWithPath: #filePath)
        var resolved: String?
        let pending = PendingFilePicker(
            requestId: "database", kind: .openDocument, mimeTypes: [], persistent: true,
            resolve: { _, uri, _, _, _ in resolved = uri }, cancel: { _ in XCTFail("Expected the original file") }
        )
        picker.enqueue(pending)
        picker.resolve(result: .success([original]), requestID: pending.id)
        XCTAssertEqual(resolved, original.absoluteString)
    }

    @MainActor
    private func request(
        _ id: String,
        resolve: @escaping () -> Void = {},
        cancel: @escaping (String) -> Void = { _ in }
    ) -> PendingFilePicker {
        PendingFilePicker(
            requestId: id, kind: .openDocument, mimeTypes: [], persistent: true,
            resolve: { _, _, _, _, _ in resolve() }, cancel: cancel
        )
    }
}
