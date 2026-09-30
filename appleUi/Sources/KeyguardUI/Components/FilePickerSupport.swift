import Foundation
import KeyguardShared
import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#endif

/// MIME-type → `UTType` mapping for the native file pickers, mirroring the
/// Compose `FilePickerEffect` mapping: the KeePass MIME types have no system
/// registration, so they resolve through the `kdbx` / `key` filename extensions.
enum FilePickerContentTypes {
    static func contentTypes(forMimeTypes mimeTypes: [String]) -> [UTType] {
        mimeTypes
            .filter { $0 != "*/*" }
            .flatMap { mimeType -> [UTType] in
                switch mimeType {
                case "application/x-kdbx", "application/x-keepass":
                    return [
                        UTType(mimeType: mimeType),
                        UTType(filenameExtension: "kdbx"),
                        UTType(filenameExtension: "key"),
                    ].compactMap { $0 }
                default:
                    return [UTType(mimeType: mimeType)].compactMap { $0 }
                }
            }
    }
}

extension URL {
    /// The display name and byte size (-1 if unknown) passed along with a picked
    /// or dropped file.
    var fileNameAndSize: (name: String, size: Int64) {
        let values = try? resourceValues(forKeys: [.fileSizeKey, .nameKey])
        return (values?.name ?? lastPathComponent, Int64(values?.fileSize ?? -1))
    }

    func securityScopedBookmarkToken() -> String? {
        #if os(macOS)
        let options: URL.BookmarkCreationOptions = [.withSecurityScope]
        #else
        let options: URL.BookmarkCreationOptions = [.minimalBookmark]
        #endif
        let data = try? bookmarkData(
            options: options,
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        )
        return data?.base64EncodedString()
    }
}

#if os(iOS)
/// Plaintext copies of picked or dropped files, which the system grants access
/// to only briefly. The shared code owns their directory: it deletes a copy once
/// it has been encrypted for upload and clears the rest on launch
/// (`AppleManagedImportFiles.kt`).
enum ManagedImportCopy {
    /// Copies `url` into a unique directory, so the original name is preserved.
    static func copy(from url: URL) throws -> URL {
        let name = (try? url.resourceValues(forKeys: [.nameKey]))?.name ?? url.lastPathComponent
        let directory = URL(fileURLWithPath: ManagedImportFilesKt.nextManagedImportDirectory(), isDirectory: true)
        let destination = directory.appendingPathComponent(name)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: url, to: destination)
        return destination
    }
}

/// A file dropped from another app. The system only lends it for the
/// duration of the import, so it lands as a managed copy.
struct DroppedFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .item) { received in
            DroppedFile(url: try ManagedImportCopy.copy(from: received.file))
        }
    }
}

extension View {
    /// Presents the pending file request that `presents` claims as the system
    /// file importer, and resolves that request with the selection or the cancellation.
    func pendingFileImporter(
        defaultContentTypes: [UTType] = [.data, .item],
        where presents: @escaping (PendingFilePicker) -> Bool
    ) -> some View {
        modifier(PendingFileImporterModifier(defaultContentTypes: defaultContentTypes, presents: presents))
    }
}

private struct PendingFileImporterModifier: ViewModifier {
    @Environment(FilePickerModel.self) private var filePickerModel
    let defaultContentTypes: [UTType]
    let presents: (PendingFilePicker) -> Bool

    func body(content: Content) -> some View {
        let request = filePickerModel.pendingFilePicker.flatMap { presents($0) ? $0 : nil }
        // Only the callbacks may consume the request: SwiftUI resets the
        // binding before either of them arrives.
        content.fileImporter(
            isPresented: Binding(get: { request != nil }, set: { _ in }),
            allowedContentTypes: request?.allowedContentTypes ?? defaultContentTypes,
            allowsMultipleSelection: false,
            onCompletion: { result in
                filePickerModel.resolveFilePicker(result: result, requestId: request?.requestId)
            },
            onCancellation: {
                filePickerModel.cancelFilePicker(requestId: request?.requestId)
            }
        )
    }
}

/// Reports Cancel through the same completion as a selection, as a
/// `CocoaError(.userCancelled)` failure.
struct DocumentOpenPicker: UIViewControllerRepresentable {
    let request: PendingFilePicker
    let completion: (Result<[URL], Error>) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(completion: completion)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: request.allowedContentTypes,
            asCopy: false
        )
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let completion: (Result<[URL], Error>) -> Void

        init(completion: @escaping (Result<[URL], Error>) -> Void) {
            self.completion = completion
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            completion(.success(urls))
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            completion(.failure(CocoaError(.userCancelled)))
        }
    }
}

struct DocumentExportPicker: UIViewControllerRepresentable {
    let export: PendingFileExport

    func makeCoordinator() -> Coordinator {
        Coordinator(export: export)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("keyguard-export", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let source = tempDir.appendingPathComponent(export.suggestedName)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: source.path, contents: Data())

        let picker = UIDocumentPickerViewController(forExporting: [source], asCopy: false)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let export: PendingFileExport

        init(export: PendingFileExport) {
            self.export = export
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else {
                export.cancel(export.requestId)
                return
            }
            let didAccess = url.startAccessingSecurityScopedResource()
            defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
            let token = url.securityScopedBookmarkToken()
            export.resolve(export.requestId, url.absoluteString, url.lastPathComponent, 0, token)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            export.cancel(export.requestId)
        }
    }
}
#endif

extension View {
    /// Accepts a single dropped file while `text` is non-nil, showing `text`
    /// over the view while a drag hovers it. Mirrors the Compose `FileDropTargetBox`.
    func fileDropTarget(text: String?, onDrop: @escaping (URL) -> Void) -> some View {
        modifier(FileDropTargetModifier(text: text, onDrop: onDrop))
    }
}

private struct FileDropTargetModifier: ViewModifier {
    let text: String?
    let onDrop: (URL) -> Void

    @State private var isTargeted = false

    @ViewBuilder
    func body(content: Content) -> some View {
        if let text {
            acceptingDrops(content)
                .overlay {
                    if isTargeted {
                        FileDropOverlay(text: text)
                    }
                }
        } else {
            content
        }
    }

    #if os(macOS)
    // The original file: a drop grants the sandbox access to it.
    private func acceptingDrops(_ content: Content) -> some View {
        content.dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first, url.isFileURL else { return false }
            onDrop(url)
            return true
        } isTargeted: {
            isTargeted = $0
        }
    }
    #else
    private func acceptingDrops(_ content: Content) -> some View {
        content.dropDestination(for: DroppedFile.self) { files, _ in
            guard let file = files.first else { return false }
            onDrop(file.url)
            return true
        } isTargeted: {
            isTargeted = $0
        }
    }
    #endif
}

private struct FileDropOverlay: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.callout.weight(.medium))
            .multilineTextAlignment(.center)
            .foregroundStyle(.tint)
            .padding(12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(.tint, style: StrokeStyle(lineWidth: 2, dash: [6, 4]))
            }
            .allowsHitTesting(false)
    }
}
