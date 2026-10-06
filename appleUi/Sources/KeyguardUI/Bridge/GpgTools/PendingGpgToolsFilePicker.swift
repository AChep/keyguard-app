import Foundation

struct PendingGpgToolsFilePicker: Identifiable {
    let id: String
    let destinationURL: URL
    let observationId: UUID
}
