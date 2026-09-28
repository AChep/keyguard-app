import Foundation
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
/// Unlike SwiftUI's fileImporter completion, this delegate also reports Cancel.
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
