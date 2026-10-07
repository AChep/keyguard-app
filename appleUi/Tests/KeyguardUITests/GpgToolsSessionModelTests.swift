import Foundation
import KeyguardShared
import XCTest
@testable import KeyguardUI

final class GpgToolsSessionModelTests: XCTestCase {
    @MainActor
    func testWorkspacesKeepDraftsOperationsActionsAndResultsIndependent() async throws {
        let a = GpgToolsSourceProbe()
        let b = GpgToolsSourceProbe()
        let first = GpgToolsModel(source: a)
        let second = GpgToolsModel(source: b)
        first.startGpgToolsObservation(operation: "encrypt")
        second.startGpgToolsObservation(operation: "encrypt")
        a.changes[0](snapshot("first"))
        b.changes[0](snapshot("second"))
        try await settle()
        XCTAssertEqual(first.gpgTools.inputText, "first")
        XCTAssertEqual(second.gpgTools.inputText, "second")
        first.setGpgToolsInputText("edited")
        second.runGpgTools()
        first.startGpgToolsObservation(operation: "decrypt")
        a.results[0](result("stale"))
        b.results[0](result("second"))
        try await settle()
        XCTAssertNil(first.gpgToolsResult)
        XCTAssertEqual(second.gpgToolsResult?.id, "second")
        XCTAssertEqual(a.actions, ["input:edited"])
        XCTAssertEqual(b.actions, ["run"])
        first.close()
        first.startGpgToolsObservation(operation: "sign")
        first.setGpgToolsInputText("late")
        a.results[1](result("closed"))
        b.changes[0](snapshot("still live"))
        try await settle()
        XCTAssertEqual(a.operations, ["encrypt", "decrypt"])
        XCTAssertEqual(a.actions, ["input:edited"])
        XCTAssertNil(first.gpgToolsResult)
        XCTAssertEqual(a.closes, 1)
        XCTAssertEqual(b.cancellations, 0)
        XCTAssertEqual(second.gpgTools.inputText, "still live")
        second.close()
    }

    @MainActor
    func testLatePickersAndExportPreparationReleaseOnlyTheirOriginalSession() async throws {
        let a = GpgToolsSourceProbe()
        let b = GpgToolsSourceProbe()
        let first = GpgToolsModel(source: a)
        let second = GpgToolsModel(source: b)
        first.startGpgToolsObservation(operation: "encrypt")
        second.startGpgToolsObservation(operation: "encrypt")
        a.results[0](result("export"))
        try await settle()
        first.invokeGpgToolsResultSave()
        XCTAssertEqual(a.exports.count, 1)
        first.close()
        a.files[0](GpgToolsFilePickerRequest(id: "late file", destinationUri: "file:///tmp/unused"))
        a.keys[0](GpgToolsPublicKeyRequest(id: "late key", text: ""))
        a.exports[0](GpgToolsExportSnapshot(id: "lease", uri: "file:///tmp/unused", name: "unused"))
        try await settle()
        XCTAssertEqual(a.resolvedFiles, ["late file"])
        XCTAssertEqual(a.finishedKeys, ["late key"])
        XCTAssertEqual(a.finishedExports, ["lease"])
        XCTAssertTrue(b.resolvedFiles.isEmpty)
        XCTAssertTrue(b.finishedKeys.isEmpty)
        XCTAssertTrue(b.finishedExports.isEmpty)
        XCTAssertNil(first.pendingGpgToolsFilePicker)
        XCTAssertNil(first.pendingGpgToolsPublicKey)
        XCTAssertNil(first.pendingGpgToolsExport)
        second.close()
    }

    @MainActor
    func testLockClearsNativePresentationStateAndClosingIsIdempotent() async throws {
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "encrypt")
        source.results[0](result("result"))
        source.keys[0](GpgToolsPublicKeyRequest(id: "key", text: "key"))
        try await settle()
        source.changes[0](.companion.empty)
        try await settle()
        XCTAssertNil(model.gpgToolsResult)
        XCTAssertNil(model.pendingGpgToolsPublicKey)
        XCTAssertEqual(source.finishedKeys, ["key"])
        model.close()
        model.close()
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.cancellations, 1)
    }

    @MainActor
    func testReleasingWorkspaceClosesItsSourceAndObservation() async throws {
        let source = GpgToolsSourceProbe()
        weak var released: GpgToolsModel?
        do {
            let model = GpgToolsModel(source: source)
            released = model
            model.startGpgToolsObservation(operation: "sign")
        }
        try await settle()
        XCTAssertNil(released)
        XCTAssertEqual(source.closes, 1)
        XCTAssertEqual(source.cancellations, 1)
    }

    #if os(iOS)
    @MainActor
    func testPickedFileStagesBeforeResolvingAndIgnoresDismissalCancellation() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let original = directory.appendingPathComponent("input.txt")
        let destination = directory.appendingPathComponent("staged.txt")
        let bytes = Data("GPG picker regression\n".utf8)
        try bytes.write(to: original)
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "encrypt")
        source.files[0](GpgToolsFilePickerRequest(id: "input", destinationUri: destination.absoluteString))
        try await settle()
        model.completeGpgToolsFilePicker(id: "input", result: .success(original))
        model.cancelGpgToolsFilePicker(id: "input")
        for _ in 0..<100 where source.resolvedFiles.isEmpty { try await settle() }
        XCTAssertEqual(source.resolvedFiles, ["input"])
        XCTAssertEqual(source.resolvedFileDetails.first?.name, "input.txt")
        XCTAssertEqual(source.resolvedFileDetails.first?.size, Int64(bytes.count))
        XCTAssertEqual(try Data(contentsOf: destination), bytes)
        XCTAssertFalse(model.gpgToolsNativeBusy)
        XCTAssertNil(model.gpgToolsError)
        model.close()
    }

    @MainActor
    func testCancelledInputIgnoresLateSelection() async throws {
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "encrypt")
        source.files[0](GpgToolsFilePickerRequest(id: "input", destinationUri: "file:///tmp/unused"))
        try await settle()
        model.cancelGpgToolsFilePicker(id: "input")
        model.completeGpgToolsFilePicker(id: "input", result: .success(URL(fileURLWithPath: "/tmp/late")))
        XCTAssertEqual(source.resolvedFiles, ["input"])
        XCTAssertNil(source.resolvedFileDetails.first?.name)
        XCTAssertFalse(model.gpgToolsNativeBusy)
        model.close()
    }

    @MainActor
    func testExportCancellationFailureAndRetryReleaseEachLeaseOnce() async throws {
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "sign")
        source.results[0](result("result"))
        try await settle()
        model.invokeGpgToolsResultSave()
        source.exports[0](GpgToolsExportSnapshot(id: "cancelled", uri: "file:///tmp/unused", name: "output.asc"))
        try await settle()
        let cancelled = try XCTUnwrap(model.pendingGpgToolsExport)
        model.completeGpgToolsExport(cancelled, result: .success(false))
        try await settle()
        XCTAssertFalse(model.gpgToolsExportSucceeded)
        XCTAssertFalse(model.gpgToolsNativeBusy)
        XCTAssertNil(model.gpgToolsError)
        model.invokeGpgToolsResultSave()
        source.exports[1](GpgToolsExportSnapshot(id: "failed", uri: "file:///tmp/unused", name: "output.asc"))
        try await settle()
        let failed = try XCTUnwrap(model.pendingGpgToolsExport)
        model.completeGpgToolsExport(failed, result: .failure(CocoaError(.fileWriteNoPermission)))
        XCTAssertNotNil(model.gpgToolsError)
        XCTAssertFalse(model.gpgToolsNativeBusy)
        model.invokeGpgToolsResultSave()
        source.exports[2](GpgToolsExportSnapshot(id: "saved", uri: "file:///tmp/unused", name: "output.asc"))
        try await settle()
        let saved = try XCTUnwrap(model.pendingGpgToolsExport)
        model.completeGpgToolsExport(saved, result: .success(true))
        model.completeGpgToolsExport(saved, result: .success(false))
        await Task.detached { saved.lease.release() }.value
        try await settle()
        XCTAssertEqual(source.finishedExports, ["cancelled", "failed", "saved"])
        XCTAssertTrue(model.gpgToolsExportSucceeded)
        XCTAssertFalse(model.gpgToolsNativeBusy)
        XCTAssertNil(model.gpgToolsError)
        model.close()
    }

    @MainActor
    func testActiveExportSurvivesWorkspaceCloseAndRejectsStaleCompletion() async throws {
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "sign")
        source.results[0](result("result"))
        try await settle()
        model.invokeGpgToolsResultSave()
        source.exports[0](GpgToolsExportSnapshot(id: "lease", uri: "file:///tmp/unused", name: "output.asc"))
        try await settle()
        let nativeRequest = try XCTUnwrap(model.pendingGpgToolsExport)
        model.close()
        try await settle()
        XCTAssertTrue(source.finishedExports.isEmpty)
        model.completeGpgToolsExport(nativeRequest, result: .success(true))
        try await settle()
        XCTAssertEqual(source.finishedExports, ["lease"])
        XCTAssertFalse(model.gpgToolsExportSucceeded)
        XCTAssertNil(model.gpgToolsResult)
    }

    @MainActor
    func testAbandonedNativeExportReleasesOnLastReference() async throws {
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "sign")
        source.results[0](result("result"))
        try await settle()
        model.invokeGpgToolsResultSave()
        source.exports[0](GpgToolsExportSnapshot(id: "lease", uri: "file:///tmp/unused", name: "output.asc"))
        try await settle()
        var nativeFile = model.pendingGpgToolsExport.map { GpgToolsExportFile(request: $0) }
        XCTAssertEqual(nativeFile?.suggestedFilename, "output.asc")
        model.close()
        try await settle()
        XCTAssertEqual(nativeFile?.request.id, "lease")
        XCTAssertTrue(source.finishedExports.isEmpty)
        nativeFile = nil
        try await settle()
        XCTAssertEqual(source.finishedExports, ["lease"])
    }

    @MainActor
    func testUnpresentedExportReleasesOnClose() async throws {
        let source = GpgToolsSourceProbe()
        let model = GpgToolsModel(source: source)
        model.startGpgToolsObservation(operation: "sign")
        source.results[0](result("result"))
        try await settle()
        model.invokeGpgToolsResultSave()
        source.exports[0](GpgToolsExportSnapshot(id: "lease", uri: "file:///tmp/unused", name: "output.asc"))
        try await settle()
        model.close()
        try await settle()
        XCTAssertEqual(source.finishedExports, ["lease"])
    }
    #endif

    private func snapshot(_ text: String) -> GpgToolsSnapshot {
        GpgToolsSnapshot(
            loaded: true, operation: "encrypt", scope: "text", scopes: [], signMode: "cleartext", signModes: [],
            verifyMode: "inline", verifyModes: [], armor: true, showArmor: false,
            inputText: text, inputTextRevision: 0, inputLabel: "", showSignatureField: false,
            signatureText: "", signatureTextRevision: 0, storedKeys: [], selectedPrivateKeyId: nil,
            selectedEncryptSigningKeyId: nil, selectedRecipientIds: [], busy: false, canRun: true,
            inputFile: nil, signatureFile: nil, customPublicKeys: [])
    }

    private func result(_ id: String) -> GpgToolsResultSnapshot {
        GpgToolsResultSnapshot(
            title: "Result", notes: [], outputLabel: nil, outputText: "Output",
            incognito: false, canCopy: true, canSave: true, id: id, file: nil)
    }

    private func settle() async throws { try await Task.sleep(for: .milliseconds(20)) }
}
