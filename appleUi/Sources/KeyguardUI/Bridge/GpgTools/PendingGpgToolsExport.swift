import Foundation

struct PendingGpgToolsExport: Identifiable {
    let id: String
    let artifactURL: URL
    let name: String
    let observationId: UUID
    let resultId: String
}
