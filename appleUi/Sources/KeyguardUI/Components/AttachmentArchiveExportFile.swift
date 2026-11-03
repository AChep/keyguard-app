import Foundation
#if os(iOS)
import CoreTransferable
import UniformTypeIdentifiers
#endif

/// Keep the protected staging file alive until the native exporter has finished.
struct AttachmentArchiveExportFile: Identifiable, Sendable {
    let id: String
    let url: URL
    let name: String
    let lease: GpgToolsExportLease
}

#if os(iOS)
extension AttachmentArchiveExportFile: Transferable {
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { file in
            SentTransferredFile(file.url)
        }
        .suggestedFileName { $0.name }
    }
}
#endif
