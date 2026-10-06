import Foundation
import SwiftUI
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#else
import UIKit
#endif

struct GpgToolsImportedFile: Sendable {
    let displayName: String
    let byteCount: Int64
    let originalURL: URL
}

enum GpgToolsFileError: Error {
    case notRegularFile
    case missingSelection
    case inputWouldBeOverwritten
    case coordinationFailed
}

/// The caller owns staging directories and keeps input/output leases alive until
/// these operations (including cancelled operations) have actually returned.
enum GpgToolsFileSupport {
    static func importFile(from source: URL, to destination: URL) async throws -> GpgToolsImportedFile {
        let worker = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let accessing = source.startAccessingSecurityScopedResource()
            defer { if accessing { source.stopAccessingSecurityScopedResource() } }
            var coordinationError: NSError?
            var result: Result<GpgToolsImportedFile, Error>?
            NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) {
                readableURL in
                result = Result {
                    try requireRegularFile(readableURL)
                    let byteCount = try copyAndPublish(from: readableURL, to: destination, privateArtifact: true)
                    return GpgToolsImportedFile(
                        displayName: source.lastPathComponent,
                        byteCount: byteCount,
                        originalURL: source
                    )
                }
            }
            if let coordinationError { throw coordinationError }
            guard let result else { throw GpgToolsFileError.coordinationFailed }
            return try result.get()
        }
        return try await withTaskCancellationHandler {
            try await worker.value
        } onCancel: {
            worker.cancel()
        }
    }

    #if os(macOS)
    @MainActor
    static func chooseInput() async throws -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.treatsFilePackagesAsDirectories = true
        panel.resolvesAliases = true
        let response = try await present(panel)
        guard response == .OK else { return nil }
        guard let url = panel.url else { throw GpgToolsFileError.missingSelection }
        return url
    }

    @MainActor
    static func exportFile(
        _ artifactURL: URL,
        suggestedName: String,
        excluding originalURLs: [URL] = []
    ) async throws -> Bool {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = URL(fileURLWithPath: suggestedName).lastPathComponent
        panel.canCreateDirectories = true
        panel.isExtensionHidden = false
        let response = try await present(panel)
        guard response == .OK else { return false }
        guard let destination = panel.url else { throw GpgToolsFileError.missingSelection }
        let worker = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let accessing = destination.startAccessingSecurityScopedResource()
            defer { if accessing { destination.stopAccessingSecurityScopedResource() } }
            var coordinationError: NSError?
            var result: Result<Void, Error>?
            NSFileCoordinator().coordinate(
                readingItemAt: artifactURL,
                options: [],
                writingItemAt: destination,
                options: .forReplacing,
                error: &coordinationError
            ) { readableURL, writableURL in
                result = Result {
                    try requireRegularFile(readableURL)
                    for original in originalURLs + [artifactURL] {
                        let originalAccess = original.startAccessingSecurityScopedResource()
                        defer { if originalAccess { original.stopAccessingSecurityScopedResource() } }
                        guard !sameFile(original, writableURL) else {
                            throw GpgToolsFileError.inputWouldBeOverwritten
                        }
                    }
                    _ = try copyAndPublish(from: readableURL, to: writableURL, privateArtifact: false)
                }
            }
            if let coordinationError { throw coordinationError }
            guard let result else { throw GpgToolsFileError.coordinationFailed }
            try result.get()
        }
        try await withTaskCancellationHandler {
            try await worker.value
        } onCancel: {
            worker.cancel()
        }
        return true
    }

    @MainActor
    private static func present(_ panel: NSSavePanel) async throws -> NSApplication.ModalResponse {
        try Task.checkCancellation()
        let response = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                panel.begin { continuation.resume(returning: $0) }
            }
        } onCancel: {
            Task { @MainActor in panel.cancel(nil) }
        }
        try Task.checkCancellation()
        return response
    }
    #endif

    private static func requireRegularFile(_ url: URL) throws {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isPackageKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isPackage != true, values.isSymbolicLink != true else {
            throw GpgToolsFileError.notRegularFile
        }
    }

    private static func sameFile(_ lhs: URL, _ rhs: URL) -> Bool {
        if lhs.standardizedFileURL.resolvingSymlinksInPath() == rhs.standardizedFileURL.resolvingSymlinksInPath() {
            return true
        }
        let keys: Set<URLResourceKey> = [.fileResourceIdentifierKey, .volumeIdentifierKey]
        guard let left = try? lhs.resourceValues(forKeys: keys),
            let right = try? rhs.resourceValues(forKeys: keys),
            let leftFile = left.fileResourceIdentifier as? NSObject,
            let rightFile = right.fileResourceIdentifier as? NSObject,
            let leftVolume = left.volumeIdentifier as? NSObject,
            let rightVolume = right.volumeIdentifier as? NSObject
        else { return false }
        return leftFile.isEqual(rightFile) && leftVolume.isEqual(rightVolume)
    }

    /// Copy with bounded memory into a sibling before replacing the destination.
    /// Existing destination contents remain intact if reading or writing fails.
    private static func copyAndPublish(from source: URL, to destination: URL, privateArtifact: Bool) throws -> Int64 {
        try Task.checkCancellation()
        guard !sameFile(source, destination) else { throw GpgToolsFileError.inputWouldBeOverwritten }
        let manager = FileManager.default
        var temporary = destination.deletingLastPathComponent()
            .appendingPathComponent(".keyguard-gpg-" + UUID().uuidString)
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o600]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.complete
        #endif
        guard manager.createFile(atPath: temporary.path, contents: nil, attributes: attributes) else {
            throw CocoaError(.fileWriteUnknown)
        }
        defer { try? manager.removeItem(at: temporary) }
        if privateArtifact {
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try temporary.setResourceValues(values)
        }

        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let output = try FileHandle(forWritingTo: temporary)
        defer { try? output.close() }
        var byteCount: Int64 = 0
        while true {
            try Task.checkCancellation()
            let chunk = try input.read(upToCount: 1024 * 1024) ?? Data()
            if chunk.isEmpty { break }
            try output.write(contentsOf: chunk)
            byteCount += Int64(chunk.count)
        }
        try output.synchronize()
        try output.close()
        try input.close()
        try Task.checkCancellation()

        if manager.fileExists(atPath: destination.path) {
            try requireRegularFile(destination)
            _ = try manager.replaceItemAt(
                destination,
                withItemAt: temporary,
                backupItemName: nil,
                options: privateArtifact ? .usingNewMetadataOnly : []
            )
        } else {
            try manager.moveItem(at: temporary, to: destination)
        }
        return byteCount
    }
}

#if os(iOS)
struct GpgToolsInputPicker: UIViewControllerRepresentable {
    let onCompletion: (Result<URL?, Error>) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCompletion: onCompletion) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        picker.presentationController?.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {
        controller.presentationController?.delegate = context.coordinator
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate, UIAdaptivePresentationControllerDelegate {
        private var onCompletion: ((Result<URL?, Error>) -> Void)?

        init(onCompletion: @escaping (Result<URL?, Error>) -> Void) { self.onCompletion = onCompletion }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else {
                finish(.failure(GpgToolsFileError.missingSelection))
                return
            }
            finish(.success(url))
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(.success(nil)) }
        func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
            finish(.success(nil))
        }

        private func finish(_ result: Result<URL?, Error>) {
            let callback = onCompletion
            onCompletion = nil
            callback?(result)
        }
    }
}

/// The source must already contain the completed output and remain alive until
/// completion or cancellation. The picker exports a copy, never the owned file.
struct GpgToolsExportPicker: UIViewControllerRepresentable {
    let artifactURL: URL
    let onCompletion: (Result<Bool, Error>) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCompletion: onCompletion) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [artifactURL], asCopy: true)
        picker.delegate = context.coordinator
        picker.presentationController?.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {
        controller.presentationController?.delegate = context.coordinator
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate, UIAdaptivePresentationControllerDelegate {
        private var onCompletion: ((Result<Bool, Error>) -> Void)?

        init(onCompletion: @escaping (Result<Bool, Error>) -> Void) { self.onCompletion = onCompletion }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if urls.isEmpty {
                finish(.failure(GpgToolsFileError.missingSelection))
            } else {
                finish(.success(true))
            }
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(.success(false)) }
        func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
            finish(.success(false))
        }

        private func finish(_ result: Result<Bool, Error>) {
            let callback = onCompletion
            onCompletion = nil
            callback?(result)
        }
    }
}
#endif
