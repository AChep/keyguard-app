#if os(iOS)
import CoreTransferable
import UniformTypeIdentifiers

/// The file and its lease travel together until the exporter finishes copying.
struct GpgToolsExportFile: Transferable {
    let request: PendingGpgToolsExport

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { file in
            SentTransferredFile(file.request.artifactURL)
        }
        .suggestedFileName { $0.request.name }
    }
}
#endif
